package com.example.geoalarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.example.geoalarm.network.WorkerProfile
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private val TAG = "LoginActivity"
    private var isPasswordVisible = false

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

        // Display current active cloud server
        tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"

        // 1. One-Time Persistent Login Check: If already authenticated, skip login screen
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val savedWorkerId = userPrefs.getString("WORKER_ID", "")
        if (!savedWorkerId.isNullOrEmpty()) {
            val savedWorkerName = userPrefs.getString("WORKER_NAME", "Worker") ?: "Worker"
            proceedToMain(savedWorkerId, savedWorkerName)
            return
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
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty()) {
                etUsername.error = "Please enter your Tally ID / Worker ID"
                etUsername.requestFocus()
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                etPassword.error = "Please enter your password (default: password)"
                etPassword.requestFocus()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "Authenticating..."
            tvConnectionStatus?.text = "Connecting to ${ApiClient.getBaseUrl()}..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Authenticating worker: $username against ${ApiClient.getBaseUrl()}")
                    
                    val loginRequest = MobileLoginRequest(
                        workerId = username,
                        password = password
                    )
                    
                    val response = ApiClient.apiService.login(loginRequest)

                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"
                        tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"

                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            val worker = body.worker
                            val jobs = body.jobs ?: emptyList()

                            if (worker != null) {
                                val workerId = worker.tallyId.ifEmpty { worker.id ?: username }
                                val workerName = worker.name
                                val workerDesignation = worker.designation ?: "Field Technician"
                                val workerPhone = worker.phone ?: ""

                                // Save strictly verified worker session
                                userPrefs.edit()
                                    .putString("WORKER_ID", workerId)
                                    .putString("WORKER_NAME", workerName)
                                    .putString("WORKER_DESIGNATION", workerDesignation)
                                    .putString("WORKER_PHONE", workerPhone)
                                    .apply()

                                val acc = body.accommodation
                                val startLat = acc?.location?.firstOrNull()?.latitude
                                    ?: (if (jobs.isNotEmpty() && jobs[0].location.isNotEmpty()) jobs[0].location[0].latitude else 0.0)
                                val startLng = acc?.location?.firstOrNull()?.longitude
                                    ?: (if (jobs.isNotEmpty() && jobs[0].location.isNotEmpty()) jobs[0].location[0].longitude else 0.0)

                                // Dispatch Login event to backend with assigned accommodation coordinates
                                EventReporter.reportEvent(
                                    context = this@LoginActivity,
                                    eventType = "login",
                                    jobId = acc?.code ?: (if (jobs.isNotEmpty()) jobs[0].jobId else workerId),
                                    latitude = startLat,
                                    longitude = startLng
                                )

                                Toast.makeText(
                                    this@LoginActivity,
                                    "Welcome, $workerName!",
                                    Toast.LENGTH_SHORT
                                ).show()

                                proceedToMain(workerId, workerName)
                            } else {
                                showAuthErrorDialog(
                                    title = "Authentication Error",
                                    message = "Worker data was not returned by server."
                                )
                            }
                        } else {
                            // Parse server error message from JSON errorBody
                            val statusCode = response.code()
                            var errorMsg = when (statusCode) {
                                401 -> "Worker '$username' is not registered in the system.\n\nPlease register this worker on the Web Dashboard first."
                                403 -> "Worker account is deactivated.\n\nPlease contact your supervisor or administrator."
                                404 -> "Worker record not found on the server."
                                else -> "Server returned error (HTTP $statusCode)."
                            }

                            try {
                                val rawErr = response.errorBody()?.string()
                                if (!rawErr.isNullOrEmpty()) {
                                    val json = JSONObject(rawErr)
                                    if (json.has("message")) {
                                        errorMsg = json.getString("message")
                                    } else if (json.has("detail")) {
                                        errorMsg = json.getString("detail")
                                    }
                                }
                            } catch (parseEx: Exception) {
                                Log.w(TAG, "Error parsing server error body: ${parseEx.message}")
                            }

                            showAuthErrorDialog(
                                title = if (statusCode == 403) "Access Denied" else "Login Failed",
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

    private fun proceedToMain(workerId: String, workerName: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_WORKER_ID", workerId)
            putExtra("EXTRA_WORKER_NAME", workerName)
        }
        startActivity(intent)
        finish()
    }
}