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
import com.example.geoalarm.network.WorkerProfile
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val workerId = if (username.isNotEmpty()) username else "TL-8801"

            btnLogin.isEnabled = false
            btnLogin.text = "Authenticating..."
            tvConnectionStatus?.text = "Connecting to ${ApiClient.getBaseUrl()}..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Fetching profile & schedule for worker: $workerId from: ${ApiClient.getBaseUrl()}")
                    
                    // 1. Fetch worker profile
                    var workerName = workerId
                    var workerDesignation = "Field Technician"
                    var workerPhone = ""
                    try {
                        val profileRes = ApiClient.apiService.getWorkerProfile(workerId = workerId)
                        if (profileRes.isSuccessful && profileRes.body() != null) {
                            val p = profileRes.body()!!
                            workerName = p.name
                            workerDesignation = p.designation ?: "Field Technician"
                            workerPhone = p.phone ?: ""
                        }
                    } catch (pe: Exception) {
                        Log.w(TAG, "Profile fetch notice: ${pe.message}")
                    }

                    // Save worker session
                    val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    userPrefs.edit()
                        .putString("WORKER_ID", workerId)
                        .putString("WORKER_NAME", workerName)
                        .putString("WORKER_DESIGNATION", workerDesignation)
                        .putString("WORKER_PHONE", workerPhone)
                        .apply()

                    // 2. Fetch assigned jobs from backend
                    val response = ApiClient.apiService.getJobs(workerId = workerId)

                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"
                        tvConnectionStatus?.text = "Server: ${ApiClient.getBaseUrl()}"

                        if (response.isSuccessful) {
                            val jobs = response.body()?.jobs ?: emptyList()
                            
                            // 3. Dispatch immediate Login event to backend
                            EventReporter.reportEvent(
                                context = this@LoginActivity,
                                eventType = "login",
                                jobId = if (jobs.isNotEmpty()) jobs[0].jobId else workerId,
                                latitude = 10.5276,
                                longitude = 76.2144
                            )

                            Toast.makeText(
                                this@LoginActivity,
                                "Welcome, $workerName! (${jobs.size} active shifts)",
                                Toast.LENGTH_SHORT
                            ).show()
                            proceedToMain(workerId, workerName)
                        } else {
                            showConnectionFailedDialog(
                                "Server returned HTTP ${response.code()}.\nPlease check your credentials or network."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"
                        tvConnectionStatus?.text = "Server offline / unreachable"
                        showConnectionFailedDialog(
                            "Could not reach server at ${ApiClient.getBaseUrl()}.\n\n" +
                            "Error: ${e.message ?: "Network timeout"}\n\n" +
                            "Would you like to enter in offline mode?"
                        )
                    }
                }
            }
        }
    }

    private fun showConnectionFailedDialog(details: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Notice")
            .setMessage(details)
            .setPositiveButton("Enter (Offline Mode)") { _, _ ->
                val etUsername = findViewById<EditText>(R.id.etUsername)
                val u = etUsername.text.toString().trim().ifEmpty { "TL-8801" }
                proceedToMain(u, u)
            }
            .setNegativeButton("Retry", null)
            .show()
    }

    private fun proceedToMain(workerId: String = "TL-8801", workerName: String = "Worker") {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_WORKER_ID", workerId)
            putExtra("EXTRA_WORKER_NAME", workerName)
        }
        startActivity(intent)
        finish()
    }
}