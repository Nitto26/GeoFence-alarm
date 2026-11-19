package com.example.geoalarm.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface ApiService {

    // ==========================================
    // 1. AUTHENTICATION & DEVICE BINDING
    // ==========================================

    @POST("api/v1/mobile/auth/login")
    suspend fun login(
        @Body request: MobileLoginRequest
    ): Response<MobileLoginResponse>

    @POST("api/v1/mobile/auth/change-password")
    suspend fun changePassword(
        @Body request: MobileChangePasswordRequest
    ): Response<Map<String, Any>>

    @POST("api/v1/mobile/auth/refresh")
    suspend fun refreshToken(
        @Body request: MobileRefreshRequest
    ): Response<MobileLoginResponse>

    @POST("api/v1/mobile/auth/logout")
    suspend fun logout(
        @Body request: MobileRefreshRequest
    ): Response<Unit>

    // ==========================================
    // 2. POLICY & LOCATION CONSENT
    // ==========================================

    @GET("api/v1/mobile/policy/location")
    suspend fun getPolicyLocation(
        @Query("language") language: String = "en"
    ): Response<MobilePolicyResponse>

    @POST("api/v1/mobile/consent")
    suspend fun recordConsent(
        @Query("allow_password_change_pending") allowPasswordChangePending: Boolean = false,
        @Body request: MobileConsentRequest
    ): Response<Map<String, Any>>

    @POST("api/v1/mobile/consent/withdraw")
    suspend fun withdrawConsent(
        @Query("allow_password_change_pending") allowPasswordChangePending: Boolean = false,
        @Body request: MobileConsentWithdrawRequest? = null
    ): Response<Map<String, Any>>

    // ==========================================
    // 3. DEVICE METADATA
    // ==========================================

    @PUT("api/v1/mobile/device")
    suspend fun updateDevice(
        @Query("allow_password_change_pending") allowPasswordChangePending: Boolean = false,
        @Body request: MobileDeviceUpdateRequest
    ): Response<Map<String, Any>>

    // ==========================================
    // 4. HEARTBEAT & HEALTH
    // ==========================================

    @POST("api/v1/mobile/heartbeat")
    suspend fun heartbeat(
        @Body request: MobileHeartbeatRequest
    ): Response<MobileHeartbeatResponse>

    // ==========================================
    // 5. SYNCHRONIZATION ENGINE
    // ==========================================

    @GET("api/v1/mobile/sync/snapshot")
    suspend fun getSyncSnapshot(): Response<SyncSnapshotResponse>

    @GET("api/v1/mobile/sync/changes")
    suspend fun getSyncChanges(
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = 200
    ): Response<SyncChangesResponse>

    @POST("api/v1/mobile/sync/ack")
    suspend fun ackSync(
        @Body request: SyncAckRequest
    ): Response<SyncAckResponse>

    // ==========================================
    // 6. LOCATION EVENTS BATCH INGESTION
    // ==========================================

    @POST("api/v1/mobile/events/batch")
    suspend fun postLocationEvents(
        @Body request: EventBatchRequest
    ): Response<EventBatchResponse>

    // ==========================================
    // 7. PAYROLL & WORKER SLIPS
    // ==========================================

    @GET("api/v1/mobile/me/payroll")
    suspend fun getPayrollList(): Response<List<WorkerMobilePayrollSlip>>

    @GET("api/v1/mobile/me/payroll")
    suspend fun getPayrollByPeriod(
        @Query("period") period: String
    ): Response<WorkerMobilePayrollSlip>
}
