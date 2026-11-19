# 18 - Mobile Team Integration & Onboarding Guide

**Audience: Mobile Application Engineers and QA.**  
This document is the hands-on onboarding and local-testing guide complementing the authoritative [Mobile Sync and Event Contract](09-mobile-sync-contract.md) and the machine-readable OpenAPI schema [`openapi-mobile.json`](../openapi-mobile.json).

> If any detail here ever differs from `docs/09-mobile-sync-contract.md` or `openapi-mobile.json`, treat `09-mobile-sync-contract.md` and `openapi-mobile.json` as the source of truth.

---

## 1. Quick Overview (Collect Only — Server Verifies)

The Field Worker Tracker (FWT) backend is the authoritative source of truth for workforce scheduling, worksite & camp boundaries, attendance evaluation, and payroll calculation.

The mobile application:
- Stores the worker's profile, company tracking settings, accommodation polygon, and a 30-day forward sliding schedule window offline.
- Detects proximity to worksites and accommodations using server-computed geofence circles and GeoJSON polygons.
- Collects raw location events (`ENTRY`, `EXIT`, `PING`, `LOCATION_DISABLED`, `LOCATION_ENABLED`) during scheduled working hours and uploads them reliably in chronological batches over intermittent networks.
- Displays approved monthly payslips (`GET /api/v1/mobile/me/payroll`) and lets workers manage their password and tracking consent.
- **Never calculates attendance, overtime, or pay on the device** — the server independently re-verifies every uploaded coordinate against its stored PostGIS polygons and calculates attendance from immutable event logs.

---

## 2. Authentication & Device Binding Lifecycle

### Step 1: Login (`POST /api/v1/mobile/auth/login`)
- Workers authenticate with their Employee ID (`tally_id`) and password (new workers start with the company default password, e.g., `Welcome123!`).
- Device metadata **must be nested under `"device"`**:
  ```json
  {
    "tally_id": "1193",
    "password": "Welcome123!",
    "device": {
      "device_identifier": "b6c1482f-2d7c-473f-91ab-001122334455",
      "platform": "ANDROID",
      "device_model": "Samsung Galaxy A14",
      "app_version": "1.0.3",
      "os_version": "14",
      "push_token": "fcm-device-push-token"
    }
  }
  ```
- Response (`200 OK`) returns `access_token` (15 min), rotating `refresh_token` (30 days), `expires_in: 900`, `server_time_utc`, `must_change_password`, and `employee` summary.

### Step 2: Mandatory First-Login & Voluntary Password Changes
- If `must_change_password` is `true`, all operational endpoints (`/sync/*`, `/events/*`, `/me/*`) return `403 Forbidden` with code `PASSWORD_CHANGE_REQUIRED` until the worker changes their password.
- Authenticated workers can also change their password voluntarily at any time from Profile / Settings.
- **Endpoint**: `POST /api/v1/mobile/auth/change-password` (`Authorization: Bearer <access_token>`)
  ```json
  {
    "old_password": "Welcome123!",
    "new_password": "MyNewSecurePassword9"
  }
  ```
- Rules: `new_password` must be **at least 8 characters** (`PASSWORD_TOO_SHORT`) and **cannot match the company default password** (`CANNOT_REUSE_DEFAULT_PASSWORD`). Wrong `old_password` returns `400 INVALID_CURRENT_PASSWORD`.
- Response (`200 OK`): `{ "status": "PASSWORD_CHANGED", "server_time_utc": "..." }`.

### Step 3: Token Refresh & Logout
- **Refresh**: `POST /api/v1/mobile/auth/refresh` with `{ "refresh_token": "..." }` -> returns a new `access_token` and rotated `refresh_token`. Always overwrite the stored refresh token immediately.
- **Logout**: `POST /api/v1/mobile/auth/logout` with `{ "refresh_token": "..." }` -> returns `204 No Content`. Clear stored tokens and stop tracking.

### Step 4: Single-Device Binding
- The first successful login binds the worker's account to `device.device_identifier`.
- Logging in from any other device returns **`403 Forbidden`** with code **`DEVICE_MISMATCH`**.
- To switch phones, an **Admin must revoke/reset the device binding** from the web dashboard (`/devices`). Once revoked, the old device receives `403 DEVICE_NOT_ACTIVE` and must stop tracking.

---

## 3. Location Tracking Consent & Privacy Policy

Under UAE Personal Data Protection Law (Federal Decree-Law No. 45 of 2021), location tracking requires active, informed worker consent:

- **Fetch active policy**: `GET /api/v1/mobile/policy/location?language=en`  
  Returns `{ "version": 1, "language": "en", "title": "...", "body": "...", "published_at": "..." }`.
- **Record acceptance**: `POST /api/v1/mobile/consent` (`Authorization: Bearer <access_token>`)
  ```json
  {
    "policy_version": 1,
    "consented_at_utc": "2026-11-08T04:17:00Z"
  }
  ```
  Returns `200 OK`: `{ "status": "CONSENT_RECORDED", "server_time_utc": "..." }`.
- **Withdraw consent**: `POST /api/v1/mobile/consent/withdraw` (`Authorization: Bearer <access_token>`)
  ```json
  {
    "reason": "Prefer not to share location"
  }
  ```
  Returns `200 OK`: `{ "status": "CONSENT_WITHDRAWN", "server_time_utc": "..." }`.  
  Immediately stop collecting/uploading GPS events. Any further `POST /mobile/events/batch` returns `403 CONSENT_WITHDRAWN` until the worker re-accepts via `POST /mobile/consent`.
- Whenever `SETTINGS.location_policy_version` exceeds the version the worker accepted, display the updated policy and require re-acceptance before resuming tracking.

---

## 4. Device Updates, Heartbeat & Synchronization Protocol

Keep device metadata current with `PUT /api/v1/mobile/device` whenever `push_token`, `app_version`, `os_version`, or `location_permission_state` (`ALWAYS | WHILE_IN_USE | DENIED | RESTRICTED`) changes.

The sync engine uses an append-only per-worker change log with monotonic global cursors:

```
       Mobile Device                                    Backend Server
            |                                                  |
            |--- 1. GET /mobile/sync/snapshot ---------------->| (Clean install or resync)
            |<-- 200 OK: all entities + cursor 4812 -----------|
            |                                                  |
            |--- 2. Commit locally in 1 atomic transaction ----|
            |--- 3. POST /mobile/sync/ack {cursor: 4812} ----->| (Ack committed cursor)
            |<-- 200 OK: {status:"ACKNOWLEDGED", ...} ---------|
            |                                                  |
            |--- 4. POST /mobile/heartbeat {cursor: 4812} ---->| (App open / 15m / pre-alarm)
            |<-- 200 OK: {latest_cursor: 4820, has_pending:true}|
            |                                                  |
            |--- 5. GET /mobile/sync/changes?cursor=4812 ----->|
            |<-- 200 OK: coalesced deltas (next_cursor: 4820) -|
            |                                                  |
            |--- 6. Commit locally & update geofences if p=1 --|
            |--- 7. POST /mobile/sync/ack {cursor: 4820} ----->|
```

### 1. Full Snapshot (`GET /api/v1/mobile/sync/snapshot`)
Returns all active entities for the worker within the forward 30-day window:
- `PROFILE`: Employee ID, `tally_id`, name, `phone`, `email`, role, staff grade, weekly off day, `is_driver`, supervisor contact (`name`, `phone`), and `accommodation_id`.
- `SETTINGS`: Geofence circle buffer (`geofence_circle_radius_m`), approach/confirmation polling intervals, sync window days, `location_policy_version`, `alarm_lead_minutes`, `timezone`, and `upcoming` setting changes.
- `DAY_PLAN`: One entry per date (`YYYY-MM-DD`) with `status` (`WORKING | OFF | SKIPPED | LEAVE | FREE`), `marker` (`WEEKLY_OFF | PUBLIC_HOLIDAY`), resolved 5-point `planned` times, `standard_hours`, and `alarm_time`.
- `SCHEDULE_ITEM`: Individual work assignments (merging operational assignments and recurring base blueprint allocations).
- `JOB`: Worksite metadata, GeoJSON `boundary` polygon (`[lng, lat]` order), server-computed `geofence_circle`, and `boundary_missing` flag.
- `ACCOMMODATION`: Assigned living camp with GeoJSON `boundary` polygon and `geofence_circle`.

### 2. Delta Changes (`GET /api/v1/mobile/sync/changes?cursor=N&limit=200`)
- The server performs **delta coalescing**: if an entity changed multiple times after cursor `N`, only its latest full snapshot (or a `DELETE` tombstone with `payload: null`) is returned.
- **`priority: 1`** indicates a change affecting today or tomorrow — apply first and immediately re-register active geofences.
- If **`resync_required: true`** is returned (client cursor fell behind the server's **60-day** compaction floor), discard local synced entity tables (keep unsent event outbox!), call `GET /mobile/sync/snapshot`, commit, and ack.
- Loop while `has_more` is `true`.

### 3. Acknowledging Cursor (`POST /api/v1/mobile/sync/ack`)
- Call **only after** the local database transaction commits (`{ "cursor": 4820 }`).
- Returns `200 OK` with `{ "status": "ACKNOWLEDGED", "last_acked_cursor": 4820, "server_time_utc": "..." }`, updating the Web Dashboard's device sync status (`Cursor Acked / Max`).

---

## 5. Worksite & Camp Boundaries vs. Geofence Circles

To balance battery life with accurate attendance verification:
1. **OS Geofence Circle (`geofence_circle`)**:
   - `{ "center": { "lat": 25.1006, "lng": 55.20075 }, "radius_m": 480.0 }`
   - Register circles with Android `GeofencingClient` / iOS `CLCircularRegion` for today's active worksites (where `boundary_missing == false`) **and** the worker's assigned `ACCOMMODATION` (for both workers and drivers living in company camps).
   - Serves as the low-power wake-up trigger when approaching a site or camp.
2. **GeoJSON Boundary Polygon (`boundary`)**:
   - Exact polygon coordinates in WGS84 GeoJSON order (`[longitude, latitude]`).
   - Inside the circle, poll every `SETTINGS.approach_polling_interval_s` (default `60s`) and run a client-side Point-in-Polygon check.
   - Emit `ENTRY` once inside the polygon, then `PING` every `SETTINGS.confirmation_polling_interval_s` (default `300s`). When outside, confirm and emit `EXIT`.

---

## 6. Location Events Batch Ingestion (`POST /api/v1/mobile/events/batch`)

Upload up to **200 events** per batch in chronological order (oldest first):

```json
{
  "batch_id": "1c9e8d70-4b3a-4f11-90aa-bbccddeeff01",
  "device_clock_utc": "2026-11-08T07:05:00Z",
  "events": [
    {
      "event_id": "c1f7b134-8b83-4a12-9c17-f58c70d42183",
      "event_type": "ENTRY",
      "event_timestamp_utc": "2026-11-08T07:02:15Z",
      "location_captured_at_utc": "2026-11-08T07:02:15Z",
      "latitude": 25.080250,
      "longitude": 55.145250,
      "accuracy_meters": 4.8,
      "is_mock_location": false,
      "sequence_number": 42,
      "job_id": "9da30c7f-a667-4feb-b958-c12c1695de2d",
      "job_item_id": "55e42233-dddd-4a55-9876-1234567890ab",
      "accommodation_id": null
    }
  ]
}
```

- **Ingestion Rules**:
  - `event_timestamp_utc` cannot be older than **7 days** (`TIMESTAMP_TOO_OLD`) or more than **5 minutes in the future** (`TIMESTAMP_IN_FUTURE`).
  - `latitude` must be in $[-90, 90]$ and `longitude` in $[-180, 180]$ (`INVALID_COORDINATES`); nullable only for `LOCATION_DISABLED` when no prior GPS fix exists.
  - `event_id` must be a UUID generated on capture and preserved across retries. Re-sending an already stored `event_id` returns `status: "DUPLICATE"` (treat as success and delete from outbox).
  - For accommodation camp events, set `accommodation_id` and leave `job_id`/`job_item_id` as `null`.
- **Response (`200 OK`)**: Returns `batch_id`, `server_time_utc`, piggybacked `latest_cursor` (trigger `/sync/changes` if ahead), and per-event `results` (`ACCEPTED | DUPLICATE | REJECTED` with `reason`).
- **Immediate Dispatches**: Upload `LOCATION_DISABLED` (with last known fix + its earlier `location_captured_at_utc`) and `LOCATION_ENABLED` immediately as a 1-event batch so supervisors are alerted in real time.
- **Server Verification**: Every accepted event is re-evaluated via PostGIS into a verdict (`INSIDE`, `BORDERLINE`, `OUTSIDE`, `NO_POLYGON`) with integrity flags (`CLOCK_SKEW`, `LOW_ACCURACY`, `MOCK_LOCATION`, `ENTRY_OUTSIDE_POLYGON`, `EXIT_INSIDE_POLYGON`, `BORDERLINE_LOCATION`, `UNKNOWN_SITE`). See [Section 8.3 of the Contract](09-mobile-sync-contract.md#83-how-the-server-verifies-uploaded-events-transparency-for-mobile-team) for full details.

---

## 7. On-Demand Payslips (`GET /api/v1/mobile/me/payroll`)

- `GET /api/v1/mobile/me/payroll` — returns a list of all finalized (`APPROVED`) monthly payslips (`WorkerMobilePayrollSlip[]`), sorted newest first.
- `GET /api/v1/mobile/me/payroll?period=2026-10` — returns the single approved slip for that month (or `404 PAYROLL_NOT_FOUND` until approved).
- Only worker-facing figures (`standard_hours`, `worked_hours`, `paid_leave_hours`, `unpaid_leave_hours`, `salary_portion`, `missed_hours_deduction`, `ot1_hours`/`ot1_amount`, `ot2_hours`/`ot2_amount`, `gross_pay`, `net_pay`, `currency`) are exposed; employer provisions and overheads are stripped.

---

## 8. Machine-Readable OpenAPI Schema

Export the mobile-only schema anytime (automatically prunes web-admin schemas and includes all synced entity payload models):
```bash
uv run --directory backend python ../tools/export_openapi.py --tag mobile --out ../openapi-mobile.json
```
Import `openapi-mobile.json` directly into Postman, Insomnia, or OpenAPI Generator to generate Kotlin, Swift, Dart, or TypeScript client models.

---

## 9. Mobile Tester Proxy & GPS Simulator

To test every mobile endpoint end-to-end without a physical phone, run the standalone browser-based Mobile Proxy:

```bash
# 1. Start local PostgreSQL/PostGIS
docker compose up -d db mail

# 2. (Optional) Seed demo worker and worksite
uv run --directory backend python ../tools/seed_phase07_demo.py

# 3. Start backend API
uv run --directory backend uvicorn fw.main:app --reload --port 8000

# 4. Serve the Mobile Proxy
python -m http.server 8085 --directory tools/mobile-proxy
```

Open **[http://localhost:8085](http://localhost:8085)** to test:
- Login, forced first-login password change, voluntary password change, and logout
- Full snapshot & delta sync with cursor acknowledgement
- Live GPS route playback, worksite & camp boundary `ENTRY`/`PING`/`EXIT`, `LOCATION_DISABLED`/`LOCATION_ENABLED`, offline queueing, and batch upload inspection
- Approved monthly payslip viewing and full raw HTTP request/response network logs

---

## 10. Seeded Reference Worker Account (`seed_phase07_demo.py`)

| Attribute | Value |
|---|---|
| Employee ID (`tally_id`) | `1193` |
| Worker Name | Shanto Miah |
| Initial Default Password | `Welcome123!` (must change on first login) |
| Worksite Assigned | `12A14301` ("Dubai Marina Towers Landscape") |
| Accommodation | `WARSAN_01` ("Warsan Staff Accommodation Camp") |
| Shift Schedule | Active shifts 07:00 – 16:00 for the next 7 days |
