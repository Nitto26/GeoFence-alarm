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
