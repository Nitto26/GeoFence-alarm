package com.example.geoalarm.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URI
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val PREFS_NAME = "ServerSettingsPrefs"
    private const val KEY_BASE_URL = "BASE_URL"
    
    // Built-in Render Production Backend URL
    const val DEFAULT_RENDER_URL = "https://sgs-field-tracker-backend.onrender.com/"
    const val DEFAULT_BASE_URL = DEFAULT_RENDER_URL

    private var currentBaseUrl = DEFAULT_RENDER_URL
    private var retrofitInstance: Retrofit? = null

    fun init(context: Context) {
        // Always force the built-in Render server URL and overwrite any old cached URLs
        currentBaseUrl = DEFAULT_RENDER_URL
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, DEFAULT_RENDER_URL).apply()
        
        buildRetrofit()
    }

    fun getBaseUrl(): String = currentBaseUrl

    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        var formatted = trimmed
        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "https://$formatted"
        }
        if (!formatted.endsWith("/")) {
            formatted += "/"
        }
        return try {
            val uri = URI.create(formatted)
            val host = uri.host
            Retrofit.Builder().baseUrl(formatted).build()
            !host.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun setBaseUrl(context: Context, newUrl: String): Boolean {
        var formattedUrl = newUrl.trim()
        if (formattedUrl.isEmpty()) return false
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        if (!formattedUrl.endsWith("/")) {
            formattedUrl += "/"
        }

        if (!isValidUrl(formattedUrl)) {
            return false
        }

        currentBaseUrl = formattedUrl
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, formattedUrl).apply()

        buildRetrofit()
        return true
    }

    private fun buildRetrofit() {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        try {
            retrofitInstance = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        } catch (e: Exception) {
            currentBaseUrl = DEFAULT_RENDER_URL
            retrofitInstance = Retrofit.Builder()
                .baseUrl(DEFAULT_RENDER_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
    }

    val apiService: ApiService
        get() {
            if (retrofitInstance == null) {
                buildRetrofit()
            }
            return retrofitInstance!!.create(ApiService::class.java)
        }
}