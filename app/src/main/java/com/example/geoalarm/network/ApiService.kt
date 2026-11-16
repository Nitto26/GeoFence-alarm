package com.example.geoalarm.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
        @Body request: Map<String, String>
    ): Response<MobileLoginResponse>

    @POST("api/v1/mobile/auth/logout")
    suspend fun logout(): Response<Unit>

    // ==========================================
    // 2. HEARTBEAT & HEALTH
    // ==========================================

    @POST("api/v1/mobile/heartbeat")
    suspend fun heartbeat(
        @Body request: MobileHeartbeatRequest
    ): Response<MobileHeartbeatResponse>

    // ==========================================
    // 3. SYNCHRONIZATION ENGINE
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
    ): Response<Map<String, Any>>

    // ==========================================
    // 4. LOCATION EVENTS BATCH INGESTION
    // ==========================================

    @POST("api/v1/mobile/events/batch")
    suspend fun postLocationEvents(
        @Body request: EventBatchRequest
    ): Response<EventBatchResponse>
}
