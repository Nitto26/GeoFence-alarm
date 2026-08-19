package com.example.geoalarm.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
}
