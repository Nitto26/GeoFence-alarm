package com.example.geoalarm.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URI
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val PREFS_NAME = "ServerSettingsPrefs"
    private const val KEY_BASE_URL = "BASE_URL"
    
    // Built-in Render Production Backend URL
    const val DEFAULT_RENDER_URL = "https://field-worker-tracker-2.onrender.com/"
    const val DEFAULT_BASE_URL = DEFAULT_RENDER_URL

    private var currentBaseUrl = DEFAULT_RENDER_URL
    private var retrofitInstance: Retrofit? = null
    private var cachedApiService: ApiService? = null
    private var applicationContext: Context? = null

    // Allowed Backend Hostnames - Prevents arbitrary server redirection / credential harvesting
    private fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val lower = host.lowercase()
        if (lower == "field-worker-tracker-2.onrender.com" || lower == "field-worker-tracker-1.onrender.com") {
            return true
        }
        if (com.example.geoalarm.BuildConfig.DEBUG) {
            if (lower.endsWith(".onrender.com") || lower == "localhost" || lower == "10.0.2.2" || lower == "127.0.0.1") {
                return true
            }
        }
        return false
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        applicationContext = appCtx
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_BASE_URL, null)

        // Automatically migrate if empty, invalid, or pointing to an unallowed/deprecated backend
        currentBaseUrl = if (!savedUrl.isNullOrBlank() && isValidUrl(savedUrl) 
            && !savedUrl.contains("sgs-field-tracker-backend.onrender.com")
            && !savedUrl.contains("local-test-for-sgs.onrender.com")
            && !savedUrl.contains("field-worker-tracker-1.onrender.com")) {
            savedUrl
        } else {
            prefs.edit().putString(KEY_BASE_URL, DEFAULT_RENDER_URL).apply()
            DEFAULT_RENDER_URL
        }
        
        buildRetrofit()
    }

    fun getBaseUrl(): String = currentBaseUrl

    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        var formatted = trimmed
        if (formatted.startsWith("http://")) {
            formatted = formatted.replaceFirst("http://", "https://")
        } else if (!formatted.startsWith("https://")) {
            formatted = "https://$formatted"
        }
        if (!formatted.endsWith("/")) {
            formatted += "/"
        }
        return try {
            val uri = URI.create(formatted)
            val host = uri.host
            Retrofit.Builder().baseUrl(formatted).build()
            !host.isNullOrEmpty() && (uri.scheme == "https") && isAllowedHost(host)
        } catch (e: Exception) {
            false
        }
    }

    fun setBaseUrl(context: Context, newUrl: String): Boolean {
        var formattedUrl = newUrl.trim()
        if (formattedUrl.isEmpty()) return false
        if (formattedUrl.startsWith("http://")) {
            formattedUrl = formattedUrl.replaceFirst("http://", "https://")
        } else if (!formattedUrl.startsWith("https://")) {
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

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }

    private fun buildRetrofit() {
        // Redact request/response bodies from Logcat so passwords and tokens are never leaked
        val logging = HttpLoggingInterceptor().apply {
            level = if (com.example.geoalarm.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("Cookie")
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .addHeader("X-Contract-Version", "1")
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("X-App-Client", "GeoAlarm-Android")

                // Securely attach auth token if available in local session
                applicationContext?.let { ctx ->
                    val secPrefs = ctx.getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
                    val token = secPrefs.getString("AUTH_TOKEN", null)
                    if (!token.isNullOrEmpty()) {
                        requestBuilder.addHeader("Authorization", "Bearer $token")
                    }
                }

                chain.proceed(requestBuilder.build())
            }
            .authenticator { _, response ->
                if (responseCount(response) >= 3) {
                    return@authenticator null
                }

                val ctx = applicationContext ?: return@authenticator null
                val secPrefs = ctx.getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
                val refreshToken = secPrefs.getString("REFRESH_TOKEN", null) ?: return@authenticator null

                synchronized(this) {
                    val currentToken = secPrefs.getString("AUTH_TOKEN", null)
                    val originalHeader = response.request.header("Authorization")?.removePrefix("Bearer ")
                    if (!currentToken.isNullOrEmpty() && currentToken != originalHeader) {
                        return@authenticator response.request.newBuilder()
                            .header("Authorization", "Bearer $currentToken")
                            .build()
                    }

                    try {
                        val refreshJson = JSONObject().apply {
                            put("refresh_token", refreshToken)
                        }.toString()

                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val refreshRequestBody = refreshJson.toRequestBody(mediaType)
                        val refreshReq = Request.Builder()
                            .url(currentBaseUrl + "api/v1/mobile/auth/refresh")
                            .post(refreshRequestBody)
                            .addHeader("X-Contract-Version", "1")
                            .addHeader("X-Requested-With", "XMLHttpRequest")
                            .build()

                        val rawClient = OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build()

                        val refreshRes = rawClient.newCall(refreshReq).execute()
                        if (refreshRes.isSuccessful) {
                            val bodyStr = refreshRes.body?.string()
                            if (!bodyStr.isNullOrEmpty()) {
                                val json = JSONObject(bodyStr)
                                val newAccess = if (json.has("access_token")) json.getString("access_token") else null
                                val newRefresh = if (json.has("refresh_token")) json.getString("refresh_token") else null
                                if (!newAccess.isNullOrEmpty()) {
                                    val editor = secPrefs.edit().putString("AUTH_TOKEN", newAccess)
                                    if (!newRefresh.isNullOrEmpty()) {
                                        editor.putString("REFRESH_TOKEN", newRefresh)
                                    }
                                    editor.apply()

                                    return@authenticator response.request.newBuilder()
                                        .header("Authorization", "Bearer $newAccess")
                                        .build()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ApiClient", "Token refresh error: ${e.message}")
                    }
                }
                null
            }
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

        cachedApiService = retrofitInstance?.create(ApiService::class.java)
    }

    val apiService: ApiService
        get() {
            if (cachedApiService == null) {
                buildRetrofit()
            }
            return cachedApiService ?: retrofitInstance!!.create(ApiService::class.java)
        }
}
