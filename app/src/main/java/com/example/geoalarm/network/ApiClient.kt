package com.example.geoalarm.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val PREFS_NAME = "ServerSettingsPrefs"
    private const val KEY_BASE_URL = "BASE_URL"
    
    // Default URL: 10.0.2.2 is localhost on Android Emulator
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"

    private var currentBaseUrl = DEFAULT_BASE_URL
    private var retrofitInstance: Retrofit? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentBaseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        if (!currentBaseUrl.endsWith("/")) {
            currentBaseUrl += "/"
        }
        buildRetrofit()
    }

    fun getBaseUrl(): String = currentBaseUrl

    fun setBaseUrl(context: Context, newUrl: String) {
        var formattedUrl = newUrl.trim()
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "http://$formattedUrl"
        }
        if (!formattedUrl.endsWith("/")) {
            formattedUrl += "/"
        }
        currentBaseUrl = formattedUrl

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, formattedUrl).apply()

        buildRetrofit()
    }

    private fun buildRetrofit() {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        retrofitInstance = Retrofit.Builder()
            .baseUrl(currentBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: ApiService
        get() {
            if (retrofitInstance == null) {
                buildRetrofit()
            }
            return retrofitInstance!!.create(ApiService::class.java)
        }
}
