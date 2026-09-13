package com.example.geoalarm.network

import com.google.gson.annotations.SerializedName

data class LocationCoordinate(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

data class JobItem(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("location") val location: List<LocationCoordinate>,
    @SerializedName("days") val days: List<String>
)

data class MobileJobsResponse(
    @SerializedName("jobs") val jobs: List<JobItem>
)

data class LocationEventItem(
    @SerializedName("job_id") val jobId: String? = null,
    @SerializedName("worker_id") val workerId: String? = null,
    @SerializedName("location") val location: LocationCoordinate,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("event_type") val eventType: String
)

data class LocationEventRequest(
    @SerializedName("events") val events: List<LocationEventItem>
)

data class LocationEventResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("accepted_events") val acceptedEvents: Int = 0,
    @SerializedName("rejected_events") val rejectedEvents: Int = 0,
    @SerializedName("transaction_id") val transactionId: String? = null
)

data class WorkerProfile(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String,
    @SerializedName("tally_id") val tallyId: String,
    @SerializedName("designation") val designation: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("grade") val grade: String? = null,
    @SerializedName("worker_type") val workerType: String? = null,
    @SerializedName("is_active") val isActive: Boolean? = true
)

data class MobileLoginRequest(
    @SerializedName("worker_id") val workerId: String,
    @SerializedName("password") val password: String? = null
)

data class MobileLoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null,
    @SerializedName("worker") val worker: WorkerProfile? = null,
    @SerializedName("jobs") val jobs: List<JobItem>? = null,
    @SerializedName("transaction_id") val transactionId: String? = null
)