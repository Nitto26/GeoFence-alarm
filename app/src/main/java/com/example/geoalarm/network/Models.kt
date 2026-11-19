package com.example.geoalarm.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// Coordinate representation for Google Maps & local polygon ray-casting
data class LocationCoordinate(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float? = null,
    @SerializedName("is_mock") val isMock: Boolean? = false
)

data class LatLngPoint(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double
)

data class GeofenceCircle(
    @SerializedName("center") val center: LatLngPoint? = null,
    @SerializedName("radius_m") val radiusM: Float = 300f
)

data class GeoJsonPolygon(
    @SerializedName("type") val type: String? = "Polygon",
    @SerializedName("coordinates") val coordinates: List<List<List<Double>>> = emptyList()
)

// UI and Geofencing Job model used across MainActivity, RadarService, and Schedulers
data class JobItem(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("job_title") val jobTitle: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("location") val location: List<LocationCoordinate> = emptyList(),
    @SerializedName("days") val days: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("break_duration_minutes") val breakDurationMinutes: Int? = 30,
    @SerializedName("site_type") val siteType: String? = null,
    @SerializedName("is_starting_point") val isStartingPoint: Boolean? = false,
    @SerializedName("schedule_period") val schedulePeriod: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("geofence_circle") val geofenceCircle: GeofenceCircle? = null
)

data class AccommodationItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("location") val location: List<LocationCoordinate> = emptyList(),
    @SerializedName("geofence_circle") val geofenceCircle: GeofenceCircle? = null
)

// ==========================================
// 1. AUTHENTICATION & DEVICE BINDING
// ==========================================

data class MobileDeviceRegistration(
    @SerializedName("device_identifier") val deviceIdentifier: String,
    @SerializedName("platform") val platform: String = "ANDROID",
    @SerializedName("device_model") val deviceModel: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("push_token") val pushToken: String? = null
)

data class MobileLoginRequest(
    @SerializedName("tally_id") val tallyId: String,
    @SerializedName("password") val password: String,
    @SerializedName("device") val device: MobileDeviceRegistration
)

data class MobileEmployeeInfo(
    @SerializedName("employee_id") val employeeId: String,
    @SerializedName("tally_id") val tallyId: String,
    @SerializedName("name") val name: String,
    @SerializedName("role") val role: String
)

data class MobileLoginResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("expires_in") val expiresIn: Int = 900,
    @SerializedName("server_time_utc") val serverTimeUtc: String? = null,
    @SerializedName("must_change_password") val mustChangePassword: Boolean = false,
    @SerializedName("employee") val employee: MobileEmployeeInfo? = null,
    // RFC 7807 error detail properties
    @SerializedName("detail") val detail: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null
)

data class MobileChangePasswordRequest(
    @SerializedName("old_password") val oldPassword: String,
    @SerializedName("new_password") val newPassword: String
)

data class MobileRefreshRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

// Legacy alias to maintain compatibility
typealias ChangePasswordRequest = MobileChangePasswordRequest

// ==========================================
// 2. HEARTBEAT & APP HEALTH
// ==========================================

data class MobileHeartbeatRequest(
    @SerializedName("app_version") val appVersion: String,
    @SerializedName("cursor") val cursor: Long,
    @SerializedName("location_permission_state") val locationPermissionState: String? = "ALWAYS"
)

data class MobileHeartbeatResponse(
    @SerializedName("server_time_utc") val serverTimeUtc: String,
    @SerializedName("latest_cursor") val latestCursor: Long,
    @SerializedName("min_app_version") val minAppVersion: String = "1.0.0",
    @SerializedName("has_pending_changes") val hasPendingChanges: Boolean = false
)

// ==========================================
// 3. SYNCHRONIZATION ENGINE CONTRACT
// ==========================================

data class SnapshotEntity(
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("entity_version") val entityVersion: Int,
    @SerializedName("payload") val payload: JsonElement? = null
)

data class SyncSnapshotResponse(
    @SerializedName("cursor") val cursor: Long,
    @SerializedName("generated_at") val generatedAt: String? = null,
    @SerializedName("server_time_utc") val serverTimeUtc: String? = null,
    @SerializedName("window") val window: Map<String, String>? = null,
    @SerializedName("entities") val entities: List<SnapshotEntity> = emptyList()
)

data class SyncChangeItem(
    @SerializedName("cursor") val cursor: Long,
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("entity_version") val entityVersion: Int,
    @SerializedName("op") val op: String,
    @SerializedName("priority") val priority: Int = 1,
    @SerializedName("payload") val payload: JsonElement? = null
)

data class SyncChangesResponse(
    @SerializedName("from_cursor") val fromCursor: Long,
    @SerializedName("next_cursor") val nextCursor: Long,
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("resync_required") val resyncRequired: Boolean = false,
    @SerializedName("server_time_utc") val serverTimeUtc: String? = null,
    @SerializedName("changes") val changes: List<SyncChangeItem> = emptyList()
)

data class SyncAckRequest(
    @SerializedName("cursor") val cursor: Long
)

data class SyncAckResponse(
    @SerializedName("status") val status: String = "ACKNOWLEDGED",
    @SerializedName("last_acked_cursor") val lastAckedCursor: Long,
    @SerializedName("server_time_utc") val serverTimeUtc: String
)

// Entity Payloads parsed from Snapshot and Changes
data class SupervisorInfo(
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String? = null
)

data class ProfilePayload(
    @SerializedName("employee_id") val employeeId: String,
    @SerializedName("tally_id") val tallyId: String,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("role") val role: String,
    @SerializedName("staff_grade_description") val staffGradeDescription: String? = null,
    @SerializedName("weekly_off_day") val weeklyOffDay: String? = null,
    @SerializedName("is_driver") val isDriver: Boolean = false,
    @SerializedName("supervisor") val supervisor: SupervisorInfo? = null,
    @SerializedName("accommodation_id") val accommodationId: String? = null
)

data class SettingsPayload(
    @SerializedName("geofence_circle_radius_m") val geofenceCircleRadiusM: Int = 300,
    @SerializedName("approach_polling_interval_s") val approachPollingIntervalS: Int = 60,
    @SerializedName("confirmation_polling_interval_s") val confirmationPollingIntervalS: Int = 300,
    @SerializedName("schedule_sync_window_days") val scheduleSyncWindowDays: Int = 30,
    @SerializedName("location_policy_version") val locationPolicyVersion: Int = 1,
    @SerializedName("alarm_lead_minutes") val alarmLeadMinutes: Int = 30,
    @SerializedName("timezone") val timezone: String = "Asia/Dubai"
)

data class DayPlanPlanned(
    @SerializedName("accommodation_start") val accommodationStart: String? = "05:00",
    @SerializedName("first_site_start") val firstSiteStart: String? = "06:00",
    @SerializedName("break_minutes") val breakMinutes: Int = 30,
    @SerializedName("last_site_end") val lastSiteEnd: String? = "15:30",
    @SerializedName("accommodation_end") val accommodationEnd: String? = "17:00"
)

data class DayPlanPayload(
    @SerializedName("work_date") val workDate: String,
    @SerializedName("status") val status: String = "WORKING",
    @SerializedName("marker") val marker: String? = null,
    @SerializedName("planned") val planned: DayPlanPlanned? = null,
    @SerializedName("standard_hours") val standardHours: Double = 8.0,
    @SerializedName("alarm_time") val alarmTime: String? = "04:30"
)

data class AccommodationPayload(
    @SerializedName("accommodation_id") val accommodationId: String,
    @SerializedName("name") val name: String,
    @SerializedName("address") val address: String? = null,
    @SerializedName("boundary") val boundary: GeoJsonPolygon? = null,
    @SerializedName("geofence_circle") val geofenceCircle: GeofenceCircle? = null
)

data class JobPayload(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("job_number") val jobNumber: String? = null,
    @SerializedName("job_name") val jobName: String? = null,
    @SerializedName("boundary") val boundary: GeoJsonPolygon? = null,
    @SerializedName("geofence_circle") val geofenceCircle: GeofenceCircle? = null,
    @SerializedName("boundary_missing") val boundaryMissing: Boolean = false
)

data class JobItemSummary(
    @SerializedName("staff_grade_description") val staffGradeDescription: String? = null,
    @SerializedName("deployment_type") val deploymentType: String? = null,
    @SerializedName("required_staff_count") val requiredStaffCount: Int? = null,
    @SerializedName("hours_per_occurrence_per_staff") val hoursPerOccurrencePerStaff: Double? = null
)

data class ScheduleItemPayload(
    @SerializedName("scheduled_work_id") val scheduledWorkId: String,
    @SerializedName("work_date") val workDate: String,
    @SerializedName("sequence") val sequence: Int = 1,
    @SerializedName("assigned_hours") val assignedHours: Double = 3.0,
    @SerializedName("status") val status: String = "ACTIVE",
    @SerializedName("job_id") val jobId: String,
    @SerializedName("job_item_id") val jobItemId: String? = null,
    @SerializedName("sub_item_code") val subItemCode: String? = null,
    @SerializedName("occurrence_id") val occurrenceId: String? = null,
    @SerializedName("job_item_summary") val jobItemSummary: JobItemSummary? = null,
    @SerializedName("source") val source: String = "PLANNED",
    @SerializedName("is_reassignment") val isReassignment: Boolean = false
)

// ==========================================
// 4. LOCATION EVENTS BATCH INGESTION
// ==========================================

data class MobileEventItem(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("event_timestamp_utc") val eventTimestampUtc: String,
    @SerializedName("location_captured_at_utc") val locationCapturedAtUtc: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("accuracy_meters") val accuracyMeters: Float? = null,
    @SerializedName("is_mock_location") val isMockLocation: Boolean? = false,
    @SerializedName("sequence_number") val sequenceNumber: Int? = null,
    @SerializedName("job_id") val jobId: String? = null,
    @SerializedName("job_item_id") val jobItemId: String? = null,
    @SerializedName("accommodation_id") val accommodationId: String? = null
)

data class EventBatchRequest(
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("device_clock_utc") val deviceClockUtc: String,
    @SerializedName("events") val events: List<MobileEventItem>
)

data class EventResultItem(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("status") val status: String,
    @SerializedName("reason") val reason: String? = null
)

data class EventBatchResponse(
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("server_time_utc") val serverTimeUtc: String,
    @SerializedName("latest_cursor") val latestCursor: Long = 0,
    @SerializedName("results") val results: List<EventResultItem> = emptyList()
)

// ==========================================
// 5. PRIVACY POLICY & CONSENT
// ==========================================

data class MobilePolicyResponse(
    @SerializedName("version") val version: Int,
    @SerializedName("language") val language: String,
    @SerializedName("title") val title: String,
    @SerializedName("body") val body: String,
    @SerializedName("published_at") val publishedAt: String
)

data class MobileConsentRequest(
    @SerializedName("policy_version") val policyVersion: Int,
    @SerializedName("consented_at_utc") val consentedAtUtc: String
)

data class MobileConsentWithdrawRequest(
    @SerializedName("reason") val reason: String? = null
)

// ==========================================
// 6. DEVICE METADATA
// ==========================================

data class MobileDeviceUpdateRequest(
    @SerializedName("push_token") val pushToken: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("location_permission_state") val locationPermissionState: String? = null
)

// ==========================================
// 7. PAYROLL & WORKER SLIPS
// ==========================================

data class WorkerMobilePayrollSlip(
    @SerializedName("payroll_record_id") val payrollRecordId: String,
    @SerializedName("payroll_run_id") val payrollRunId: String,
    @SerializedName("month_label") val monthLabel: String,
    @SerializedName("period") val period: String? = null,
    @SerializedName("period_start") val periodStart: String,
    @SerializedName("period_end") val periodEnd: String,
    @SerializedName("status") val status: String,
    @SerializedName("employee_tally_id") val employeeTallyId: String? = null,
    @SerializedName("employee_name") val employeeName: String? = null,
    @SerializedName("currency") val currency: String? = "AED",
    @SerializedName("standard_hours") val standardHours: String,
    @SerializedName("worked_hours") val workedHours: String,
    @SerializedName("paid_leave_hours") val paidLeaveHours: String,
    @SerializedName("unpaid_leave_hours") val unpaidLeaveHours: String,
    @SerializedName("salary_portion") val salaryPortion: String,
    @SerializedName("missed_hours_deduction") val missedHoursDeduction: String,
    @SerializedName("ot1_hours") val ot1Hours: String,
    @SerializedName("ot1_amount") val ot1Amount: String,
    @SerializedName("ot2_hours") val ot2Hours: String,
    @SerializedName("ot2_amount") val ot2Amount: String,
    @SerializedName("gross_pay") val grossPay: String,
    @SerializedName("net_pay") val netPay: String,
    @SerializedName("approved_at") val approvedAt: String? = null
)

// Legacy compatibility aliases
data class WorkerProfile(
    val id: String? = null,
    val name: String,
    val tallyId: String,
    val designation: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val grade: String? = null,
    val workerType: String? = null,
    val activeDeviceId: String? = null,
    val isActive: Boolean? = true
)
