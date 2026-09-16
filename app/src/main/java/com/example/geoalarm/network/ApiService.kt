package com.example.geoalarm.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/mobile/jobs")
    suspend fun getJobs(
        @Query("worker_id") workerId: String? = null
    ): Response<MobileJobsResponse>

    @POST("api/mobile/location-events")
    suspend fun postLocationEvents(
        @Body request: LocationEventRequest
    ): Response<LocationEventResponse>

    @POST("api/mobile/login")
    suspend fun login(
        @Body request: MobileLoginRequest
    ): Response<MobileLoginResponse>

    @POST("api/mobile/change-password")
    suspend fun changePassword(
        @Body request: ChangePasswordRequest
    ): Response<ChangePasswordResponse>

    @GET("api/workers/{worker_id}")
    suspend fun getWorkerProfile(
        @Path("worker_id") workerId: String
    ): Response<WorkerProfile>

    @GET("api/workers")
    suspend fun getAllWorkers(): Response<List<WorkerProfile>>
}