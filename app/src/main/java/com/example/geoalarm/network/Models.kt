package com.example.geoalarm.network

import com.google.gson.annotations.SerializedName

data class LocationCoordinate(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

data class JobItem(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("job_title") val jobTitle: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("location") val location: List<LocationCoordinate> = emptyList(),
    @SerializedName("days") val days: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("break_duration_minutes") val breakDurationMinutes: Int? = 60,
    @SerializedName("site_type") val siteType: String? = null,
    @SerializedName("is_starting_point") val isStartingPoint: Boolean? = false,
    @SerializedName("schedule_period") val schedulePeriod: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    @SerializedName("date") val date: String? = null
)

data class AccommodationItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("location") val location: List<LocationCoordinate> = emptyList()
)

data class MobileJobsResponse(
    @SerializedName("jobs") val jobs: List<JobItem> = emptyList(),
    @SerializedName("accommodation") val accommodation: AccommodationItem? = null
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
    @SerializedName("active_device_id") val activeDeviceId: String? = null,
    @SerializedName("is_active") val isActive: Boolean? = true
)

data class MobileLoginRequest(
    @SerializedName("worker_id") val workerId: String,
    @SerializedName("password") val password: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_name") val deviceName: String? = null
)

data class MobileLoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null,
    @SerializedName("worker") val worker: WorkerProfile? = null,
    @SerializedName("jobs") val jobs: List<JobItem>? = null,
    @SerializedName("accommodation") val accommodation: AccommodationItem? = null,
    @SerializedName("transaction_id") val transactionId: String? = null
)

data class ChangePasswordRequest(
    @SerializedName("worker_id") val workerId: String,
    @SerializedName("old_password") val oldPassword: String,
    @SerializedName("new_password") val newPassword: String
)

data class ChangePasswordResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null,
    @SerializedName("transaction_id") val transactionId: String? = null
)
