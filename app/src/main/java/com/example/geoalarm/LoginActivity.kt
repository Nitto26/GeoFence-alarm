package com.example.geoalarm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import java.util.UUID
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.geoalarm.network.ApiClient
import com.example.geoalarm.network.EventReporter
import com.example.geoalarm.network.MobileLoginRequest
import com.example.geoalarm.network.SyncEngine
import com.example.geoalarm.network.WorkerProfile
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private val TAG = "LoginActivity"
    private var isPasswordVisible = false

    // Brute-force rate limiting: 5 consecutive failures triggers 30-second lockout
    private var failedLoginAttempts = 0
    private var lockoutUntilTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Initialize API Client with built-in Render server URL
        ApiClient.init(this)

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val ivTogglePassword = findViewById<ImageView>(R.id.ivTogglePassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvConnectionStatus)

        // Display current active cloud server (Debug-only switching to prevent credential harvesting)
        tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"
        if (com.example.geoalarm.BuildConfig.DEBUG) {
            tvConnectionStatus?.isClickable = true
            tvConnectionStatus?.setOnClickListener {
                val input = EditText(this)
                input.setText(ApiClient.getBaseUrl())
                AlertDialog.Builder(this)
                    .setTitle("Cloud Backend Server URL (Debug Only)")
                    .setMessage("Active Render Server. Allowed domains: *.onrender.com")
                    .setView(input)
                    .setPositiveButton("Save & Switch") { _, _ ->
                        val newUrl = input.text.toString().trim()
                        if (ApiClient.setBaseUrl(this, newUrl)) {
                            tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"
                            Toast.makeText(this, "Updated backend: ${ApiClient.getBaseUrl()}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Invalid URL or unauthorized domain", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNeutralButton("Reset Default") { _, _ ->
                        ApiClient.setBaseUrl(this, ApiClient.DEFAULT_RENDER_URL)
                        tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"
                        Toast.makeText(this, "Reset to: ${ApiClient.DEFAULT_RENDER_URL}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            tvConnectionStatus?.isClickable = false
        }

        val btnBiometricQuickUnlock = findViewById<MaterialButton>(R.id.btnBiometricQuickUnlock)

        // 1. One-Time Persistent Login Check: If already authenticated, prompt biometrics before entering
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val secPrefs = getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
        val savedWorkerId = userPrefs.getString("WORKER_ID", "")
        val savedToken = secPrefs.getString("AUTH_TOKEN", "")
        if (!savedWorkerId.isNullOrEmpty() && !savedToken.isNullOrEmpty()) {
            val savedWorkerName = userPrefs.getString("WORKER_NAME", "Worker") ?: "Worker"
            etUsername.setText(savedWorkerId)
            btnBiometricQuickUnlock?.visibility = View.VISIBLE
            btnBiometricQuickUnlock?.setOnClickListener {
                triggerBiometricLogin(savedWorkerId, savedWorkerName)
            }

            // Immediately trigger biometric prompt on launch (just like GPay)
            triggerBiometricLogin(savedWorkerId, savedWorkerName)
        }

        // Password visibility toggle
        ivTogglePassword?.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                ivTogglePassword.setColorFilter(getColor(R.color.brand_blue))
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                ivTogglePassword.setColorFilter(getColor(R.color.text_muted))
            }
            etPassword.setSelection(etPassword.text.length)
        }

        // Login Button
        btnLogin?.setOnClickListener {
            // Check rate limiting lockout
            val now = System.currentTimeMillis()
            if (now < lockoutUntilTimestamp) {
                val remainingSec = ((lockoutUntilTimestamp - now) / 1000) + 1
                Toast.makeText(this, "Too many failed attempts. Please wait $remainingSec seconds.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || !username.all { it.isDigit() }) {
                etUsername.error = "Please enter your numeric Tally Number"
                etUsername.requestFocus()
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                etPassword.error = "Please enter your password"
                etPassword.requestFocus()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "Authenticating..."
            tvConnectionStatus?.text = "Connecting to ${ApiClient.getBaseUrl()}..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Authenticating worker: $username against ${ApiClient.getBaseUrl()}")
                    
                    val androidId = getOrCreateInstallationDeviceId(username)
                    val rawDevName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    // Sanitize device name to prevent Stored XSS in web dashboard
                    val devName = rawDevName.replace(Regex("[<>&\"'/]"), "").trim().take(50)

                    val loginRequest = MobileLoginRequest(
                        tallyId = username,
                        password = password,
                        device = com.example.geoalarm.network.MobileDeviceRegistration(
                            deviceIdentifier = androidId,
                            platform = "ANDROID",
                            deviceModel = devName,
                            appVersion = "2.2",
                            osVersion = android.os.Build.VERSION.RELEASE ?: "14",
                            pushToken = null
                        )
                    )
                    
                    val response = ApiClient.apiService.login(loginRequest)

                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"
                        tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"

                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            val employee = body.employee

                            // Reset rate limiter on successful authentication
                            failedLoginAttempts = 0
                            lockoutUntilTimestamp = 0L

                            // Save session authorization tokens in secure preferences
                            val secPrefs = getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
                            secPrefs.edit()
                                .putString("AUTH_TOKEN", body.accessToken)
                                .putString("REFRESH_TOKEN", body.refreshToken)
                                .apply()

                            val workerId = employee?.tallyId ?: username
                            val workerName = employee?.name ?: "Worker"

                            // Save strictly verified worker session
                            userPrefs.edit()
                                .putString("WORKER_ID", workerId)
                                .putString("WORKER_TALLY_NO", workerId)
                                .putString("WORKER_NAME", workerName)
                                .putString("WORKER_ROLE", employee?.role ?: "WORKER")
                                .apply()

                            // Asynchronously pull initial snapshot from /api/v1/mobile/sync/snapshot
                            lifecycleScope.launch(Dispatchers.IO) {
                                SyncEngine.pullSnapshot(this@LoginActivity)
                            }

                            if (body.mustChangePassword) {
                                showForcedPasswordChangeDialog(username, password, workerId, workerName)
                            } else {
                                Toast.makeText(
                                    this@LoginActivity,
                                    "Welcome, $workerName!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                proceedToMain(workerId, workerName)
                            }
                        } else {
                            // Record failed attempt for rate limiting
                            failedLoginAttempts++
                            if (failedLoginAttempts >= 5) {
                                lockoutUntilTimestamp = System.currentTimeMillis() + 30_000L
                                failedLoginAttempts = 0
                                Toast.makeText(this@LoginActivity, "Too many failed login attempts. Locked for 30 seconds.", Toast.LENGTH_LONG).show()
                            }

                            // Parse server error message from RFC 7807 problem json
                            val statusCode = response.code()
                            var errorMsg = when (statusCode) {
                                401 -> "Invalid Tally ID or Password.\n\nPlease check your credentials and try again."
                                403 -> "Access Denied: Account or device restricted."
                                409 -> "Device Mismatch: Account is registered to another mobile device."
                                404 -> "Worker record not found on the server."
                                else -> "Server returned error (HTTP $statusCode)."
                            }

                            try {
                                val rawErr = response.errorBody()?.string()
                                if (!rawErr.isNullOrEmpty()) {
                                    val json = JSONObject(rawErr)
                                    val code = if (json.has("code")) json.getString("code") else null
                                    val detail = if (json.has("detail")) json.getString("detail") else null
                                    val message = if (json.has("message")) json.getString("message") else null

                                    errorMsg = when (code) {
                                        "DEVICE_MISMATCH" -> "Device Mismatch: This account is bound to another phone.\n\nPlease contact your supervisor or administrator to reset your device binding."
                                        "ACCOUNT_LOCKED" -> "Account Locked: Too many failed attempts or password expired.\n\nPlease contact your administrator."
                                        "ACCOUNT_DISABLED" -> "Account Disabled: Please contact your supervisor."
                                        "INVALID_CREDENTIALS" -> "Invalid Tally ID or Password."
                                        else -> detail ?: message ?: errorMsg
                                    }
                                }
                            } catch (parseEx: Exception) {
                                Log.w(TAG, "Error parsing server error body: ${parseEx.message}")
                            }

                            showAuthErrorDialog(
                                title = if (statusCode == 403 || statusCode == 409) "Access Denied" else "Login Failed",
                                message = errorMsg
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Login network failure: ${e.message}")
                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"
                        tvConnectionStatus?.text = "Server offline / unreachable"
                        showConnectionFailedDialog(
                            "Could not reach server at ${ApiClient.getBaseUrl()}.\n\n" +
                            "Error: ${e.message ?: "Network timeout"}\n\n" +
                            "Please check your internet connection and try again."
                        )
                    }
                }
            }
        }
    }

    private fun showForcedPasswordChangeDialog(username: String, currentPass: String, workerId: String, workerName: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_first_login_password, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etCurrentPwd = dialogView.findViewById<EditText>(R.id.etFirstLoginCurrentPassword)
        val etNewPwd = dialogView.findViewById<EditText>(R.id.etFirstLoginNewPassword)
        val etConfirmPwd = dialogView.findViewById<EditText>(R.id.etFirstLoginConfirmPassword)
        val tvError = dialogView.findViewById<TextView>(R.id.tvFirstLoginPasswordError)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelFirstLoginPassword)
        val btnSubmit = dialogView.findViewById<MaterialButton>(R.id.btnSubmitFirstLoginPassword)

        // Pre-fill with the default/entered password they used to log in
        etCurrentPwd.setText(currentPass)

        btnCancel.setOnClickListener {
            // Worker cancelled forced password setup - clear partial session and return to login
            val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            val secPrefs = getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
            userPrefs.edit().clear().apply()
            secPrefs.edit().clear().apply()
            dialog.dismiss()
            Toast.makeText(this, "Password setup cancelled. Please sign in again.", Toast.LENGTH_SHORT).show()
        }

        btnSubmit.setOnClickListener {
            val enteredCurrentPass = etCurrentPwd.text.toString().trim().ifEmpty { currentPass }
            val newPwd = etNewPwd.text.toString().trim()
            val confirmPwd = etConfirmPwd.text.toString().trim()

            tvError.visibility = View.GONE

            if (enteredCurrentPass.isEmpty()) {
                tvError.text = "Please enter your current default password."
                tvError.visibility = View.VISIBLE
                etCurrentPwd.requestFocus()
                return@setOnClickListener
            }

            if (newPwd.isEmpty()) {
                tvError.text = "Please enter a new password."
                tvError.visibility = View.VISIBLE
                etNewPwd.requestFocus()
                return@setOnClickListener
            }

            if (newPwd.length < 8) {
                tvError.text = "New password must be at least 8 characters long."
                tvError.visibility = View.VISIBLE
                etNewPwd.requestFocus()
                return@setOnClickListener
            }

            if (newPwd == enteredCurrentPass) {
                tvError.text = "New password cannot be the company default password."
                tvError.visibility = View.VISIBLE
                etNewPwd.requestFocus()
                return@setOnClickListener
            }

            if (!newPwd.any { it.isLetter() } || !newPwd.any { it.isDigit() }) {
                tvError.text = "New password must contain both letters and numbers."
                tvError.visibility = View.VISIBLE
                etNewPwd.requestFocus()
                return@setOnClickListener
            }

            if (newPwd != confirmPwd) {
                tvError.text = "New passwords do not match."
                tvError.visibility = View.VISIBLE
                etConfirmPwd.requestFocus()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = "Setting Password..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val req = com.example.geoalarm.network.MobileChangePasswordRequest(
                        oldPassword = enteredCurrentPass,
                        newPassword = newPwd
                    )
                    val res = ApiClient.apiService.changePassword(req)
                    withContext(Dispatchers.Main) {
                        if (res.isSuccessful) {
                            dialog.dismiss()
                            Toast.makeText(this@LoginActivity, "✓ Password set successfully! Welcome, $workerName.", Toast.LENGTH_LONG).show()
                            lifecycleScope.launch(Dispatchers.IO) {
                                SyncEngine.pullSnapshot(this@LoginActivity)
                            }
                            proceedToMain(workerId, workerName)
                        } else {
                            btnSubmit.isEnabled = true
                            btnSubmit.text = "Set Password"
                            var errMsg = "Failed to set password (${res.code()})"
                            try {
                                val errBody = res.errorBody()?.string()
                                if (!errBody.isNullOrEmpty()) {
                                    val json = JSONObject(errBody)
                                    val detail = if (json.has("detail")) json.optString("detail") else null
                                    val message = if (json.has("message")) json.optString("message") else null
                                    val code = if (json.has("code")) json.optString("code") else null
                                    errMsg = when (code) {
                                        "INVALID_CURRENT_PASSWORD" -> "Current default password does not match account."
                                        "CANNOT_REUSE_DEFAULT_PASSWORD" -> "New password cannot be the company default password."
                                        "PASSWORD_TOO_SHORT" -> "New password must be at least 8 characters long."
                                        "DEVICE_NOT_ACTIVE" -> "Device session expired or revoked. Please sign in again."
                                        else -> detail?.takeIf { it.isNotEmpty() } ?: message?.takeIf { it.isNotEmpty() } ?: errMsg
                                    }
                                }
                            } catch (_: Exception) {}
                            tvError.text = errMsg
                            tvError.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Set Password"
                        tvError.text = "Network error: ${e.message ?: "Could not reach server"}"
                        tvError.visibility = View.VISIBLE
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showAuthErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun showConnectionFailedDialog(details: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage(details)
            .setPositiveButton("Retry", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun triggerBiometricLogin(workerId: String, workerName: String) {
        if (!com.example.geoalarm.security.BiometricAuthManager.canAuthenticate(this)) {
            proceedToMain(workerId, workerName)
            return
        }

        com.example.geoalarm.security.BiometricAuthManager.promptBiometricUnlock(
            activity = this,
            title = "Unlock GeoAlarm",
            subtitle = "Verify fingerprint or PIN for $workerName",
            onSuccess = {
                proceedToMain(workerId, workerName)
            },
            onError = { _, _ ->
                Toast.makeText(this, "Biometric authentication cancelled. Tap below or log in with password.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun proceedToMain(workerId: String, workerName: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_WORKER_ID", workerId)
            putExtra("EXTRA_WORKER_NAME", workerName)
        }
        startActivity(intent)
        finish()
    }

    @SuppressLint("HardwareIds")
    private fun getOrCreateInstallationDeviceId(fallbackUser: String): String {
        val prefs = getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("DEVICE_INSTALLATION_ID", null)
        if (deviceId.isNullOrEmpty()) {
            val hardwareId = try {
                android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
            } catch (_: Exception) { null }
            deviceId = hardwareId ?: UUID.randomUUID().toString()
            prefs.edit().putString("DEVICE_INSTALLATION_ID", deviceId).apply()
        }
        return deviceId ?: "dev_${fallbackUser}"
    }
}
