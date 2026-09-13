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

        // Initialize API Client with built-in Render server URL
        ApiClient.init(this)

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val ivTogglePassword = findViewById<ImageView>(R.id.ivTogglePassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvConnectionStatus)

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
            val workerId = if (username.isNotEmpty()) username else "WORKER-1001"

            btnLogin.isEnabled = false
            btnLogin.text = "Authenticating..."
            tvConnectionStatus?.text = "Connecting to Server..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Fetching schedule for worker: $workerId from: ${ApiClient.getBaseUrl()}api/mobile/jobs")
                    
                    // 1. Fetch assigned jobs from backend
                    val response = ApiClient.apiService.getJobs(workerId = workerId)

                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        btnLogin.text = "Login"

                        if (response.isSuccessful) {
                            val jobs = response.body()?.jobs ?: emptyList()
                            
                            // 2. Dispatch immediate Login event to backend
                            EventReporter.reportEvent(
                                context = this@LoginActivity,
                                eventType = "login",
                                jobId = if (jobs.isNotEmpty()) jobs[0].jobId else workerId,
                                latitude = 10.5276,
                                longitude = 76.2144
                            )

                            Toast.makeText(
                                this@LoginActivity,
                                "✓ Welcome! Active assignments loaded (${jobs.size} jobs)",
                                Toast.LENGTH_SHORT
                            ).show()
                            proceedToMain()
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
                proceedToMain()
            }
            .setNegativeButton("Retry", null)
            .show()
    }

    private fun proceedToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
