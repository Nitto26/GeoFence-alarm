package com.example.geoalarm

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

        // Initialize API Client
        ApiClient.init(this)

        val etPassword = findViewById<EditText>(R.id.etPassword)
        val ivTogglePassword = findViewById<ImageView>(R.id.ivTogglePassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val etServerUrl = findViewById<EditText>(R.id.etServerUrl)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvConnectionStatus)

        // Pre-fill Server URL
        val currentUrl = ApiClient.getBaseUrl()
        etServerUrl.setText(currentUrl)

        // Password visibility toggle
        ivTogglePassword.setOnClickListener {
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
        btnLogin.setOnClickListener {
            val serverInput = etServerUrl.text.toString().trim()
            
            // Validate URL before configuring Retrofit to prevent crashes
            if (serverInput.isNotEmpty()) {
                val success = ApiClient.setBaseUrl(this, serverInput)
                if (!success) {
                    Toast.makeText(this, "⚠ Please enter a valid Server URL", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            } else {
                Toast.makeText(this, "⚠ Server URL cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "Connecting..."
            tvConnectionStatus.text = "Connecting to ${ApiClient.getBaseUrl()}..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Testing connection to: ${ApiClient.getBaseUrl()}api/mobile/jobs")
                    
                    // 1. SEND API: Fetch assigned jobs from backend
                    val response = ApiClient.apiService.getJobs(workerId = "WORKER-1001")

                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"

                        if (response.isSuccessful) {
                            val jobs = response.body()?.jobs ?: emptyList()
                            
                            // 2. RECEIVE API: Send immediate Login event back to website
                            EventReporter.reportEvent(
                                context = this@LoginActivity,
                                eventType = "login",
                                jobId = if (jobs.isNotEmpty()) jobs[0].jobId else "WORKER-1001",
                                latitude = 10.5276,
                                longitude = 76.2144
                            )

                            Toast.makeText(
                                this@LoginActivity,
                                "✓ Connected to Laptop! Loaded ${jobs.size} jobs",
                                Toast.LENGTH_SHORT
                            ).show()
                            proceedToMain()
                        } else {
                            showConnectionFailedDialog(
                                "Server responded with HTTP ${response.code()}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"
                        tvConnectionStatus.text = "Connection failed: ${e.message}"
                        showConnectionFailedDialog(
                            "Could not reach backend at ${ApiClient.getBaseUrl()}.\n\n" +
                            "If testing over different networks (mobile data / different Wi-Fi), make sure to enter the public tunnel URL (e.g. https://...)."
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
            .setPositiveButton("Enter Anyway (Offline Demo)") { _, _ ->
                proceedToMain()
            }
            .setNegativeButton("Edit Server IP", null)
            .show()
    }

    private fun proceedToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
