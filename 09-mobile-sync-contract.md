# 09 - Mobile Sync and Event Contract

**Audience: the mobile app team.** This document is the authoritative contract between the Field Worker Tracker (FWT) backend and the worker mobile application.

The machine-readable OpenAPI schema is exported in [`openapi-mobile.json`](../openapi-mobile.json) (routes tagged `mobile`). Additive changes (new optional fields, new entity types) may appear at any time — **clients must ignore unknown fields and unknown entity types**. Breaking changes ship under a new API version path with at least 60 days' notice.

- **Contract version:** `1` (recommended header `X-Contract-Version: 1`)
- **Base path:** `/api/v1/mobile`
- **Content type:** `application/json` over HTTPS (TLS only)

---

## 1. Architecture & Core Responsibilities (Collect Only — Server Verifies)

The mobile app is strictly an **offline-first data collector and schedule/payslip viewer**:

| What the Mobile App DOES | What the Server DOES |
|---|---|
| Stores worker profile, tracking settings, accommodation polygon, and ~30 days of scheduled worksites + polygons locally. | Resolves effective-dated time rules, base blueprints, operational assignments, leaves, public holidays, and polygons into the worker's sync feed. |
| Rings the morning wake-up alarm based on `DAY_PLAN.alarm_time` and activates geofence monitoring for today's assigned worksites and accommodation. | Computes geofence circles from authoritative PostGIS polygons and publishes incremental changes to a per-worker change log (`sync_changes`). |
| Runs lightweight on-device circle entry + point-in-polygon checks to decide when to emit `ENTRY`, `PING`, `EXIT`, `LOCATION_DISABLED`, and `LOCATION_ENABLED` events. | Stores every accepted raw event **immutably** in `mobile_event_logs` (never rewriting device timestamps) and **re-verifies every coordinate** against authoritative server PostGIS polygons. |
| Queues events offline and uploads them in chronological batches (`POST /mobile/events/batch`) with device clock and accuracy metadata. | Classifies every event with a server verdict (`INSIDE`, `BORDERLINE`, `OUTSIDE`, `NO_POLYGON`) and integrity flags (`CLOCK_SKEW`, `LOW_ACCURACY`, `MOCK_LOCATION`, etc.). |
| Displays the worker's schedule, profile, and server-approved monthly payslips (`GET /mobile/me/payroll`). | Builds site visits, calculates attendance, overtime, missed hours, and payroll. **The app never calculates attendance or pay.** |

> **Key rule:** The device's geofence decision (`ENTRY`/`EXIT`/`PING`) is recorded as a hint, **but the server's spatial verdict always drives attendance**. Never fabricate or modify location events on the device.

---

## 2. General Protocol Rules

- **Timestamps:** ISO-8601 UTC (`2026-11-03T05:31:07Z`). Every JSON response includes `server_time_utc` so the client can detect local clock drift.
- **Coordinates:** WGS84 decimal degrees. Note that **GeoJSON polygons use `[longitude, latitude]` order**, whereas `geofence_circle.center` and event bodies use explicit `lat`/`lng` or `latitude`/`longitude` keys.
- **Authentication:** `Authorization: Bearer <access_token>` on all endpoints except `/auth/login`, `/auth/refresh`, `/auth/logout`, and `/policy/location`.
  - Access token lifetime: `900` seconds (15 minutes).
  - Refresh token lifetime: 30 days, **rotating** (each refresh returns a new refresh token; reusing an old token revokes the session).
- **Single-device binding:** One active device per worker. The first successful login binds the account to `device.device_identifier`. Logging in from any other device fails with `403 DEVICE_MISMATCH` until an Admin revokes the old device binding.
- **Last-seen tracking:** Any authenticated mobile request updates `last_seen_at` on the registered device.
- **Error format:** RFC 7807 (`application/problem+json`) with a stable machine-readable `code` field:
  ```json
  {
    "code": "DEVICE_MISMATCH",
    "message": "This account is bound to another device. Contact Admin to reset binding."
  }
  ```
- **Rate limits:** Login locks after repeated failures; on `429 RATE_LIMITED`, honour `Retry-After`.

---

## 3. Endpoint Inventory Overview

| Category | Method & Path | Auth Required | Purpose |
|---|---|---|---|
| **Auth** | `POST /api/v1/mobile/auth/login` | No | Authenticate worker & bind device |
| **Auth** | `POST /api/v1/mobile/auth/change-password` | Bearer (allowed when password change pending) | Mandatory first-login or voluntary password update |
| **Auth** | `POST /api/v1/mobile/auth/refresh` | No (uses refresh token) | Rotate refresh token & obtain fresh access token |
| **Auth** | `POST /api/v1/mobile/auth/logout` | No (uses refresh token) | Revoke refresh session |
| **Policy & Consent** | `GET /api/v1/mobile/policy/location` | No | Fetch active location tracking privacy notice |
| **Policy & Consent** | `POST /api/v1/mobile/consent` | Bearer | Record worker acceptance of location policy |
| **Policy & Consent** | `POST /api/v1/mobile/consent/withdraw` | Bearer | Withdraw tracking consent (halts ingestion & alerts Admin) |
| **Device** | `PUT /api/v1/mobile/device` | Bearer | Update push token, OS/app version, or location permission state |
| **Liveness** | `POST /api/v1/mobile/heartbeat` | Bearer | Report liveness, permission state & cursor; check for pending changes |
| **Sync** | `GET /api/v1/mobile/sync/snapshot` | Bearer | Full 30-day offline state handshake / resync |
| **Sync** | `GET /api/v1/mobile/sync/changes` | Bearer | Incremental coalesced delta pull since cursor |
| **Sync** | `POST /api/v1/mobile/sync/ack` | Bearer | Acknowledge highest locally committed cursor |
| **Events** | `POST /api/v1/mobile/events/batch` | Bearer | Upload up to 200 immutable location events per batch |
| **On-Demand** | `GET /api/v1/mobile/me/payroll` | Bearer | List all approved monthly payslips or fetch one by `?period=YYYY-MM` |
| **On-Demand (Planned)** | `GET /api/v1/mobile/me/scorecard` | Bearer | *Reserved for future release — not yet active* |

---

## 4. Authentication & Account Endpoints

Workers log in with **`tally_id`** (their employee ID) and a **password**. New workers are auto-provisioned on first login when they supply the company's default password (`mobile_default_password`), with `must_change_password: true`. Only an Admin can reset a worker's password back to the default or revoke a device binding.

### 4.1 `POST /api/v1/mobile/auth/login`

**Request:**
```json
{
  "tally_id": "1193",
  "password": "TempPassword123!",
  "device": {
    "device_identifier": "b6c1f8a0-9e4d-4c2a-81e3-55a2c77d1022",
    "platform": "ANDROID",
    "device_model": "Samsung Galaxy A14",
    "app_version": "1.0.3",
    "os_version": "14",
    "push_token": "fcm-token-or-null"
  }
}
```
- `device.platform`: `"ANDROID"` or `"IOS"`.
- `device_model`, `app_version`, `os_version`, `push_token` are optional (`null` allowed).

**Response (`200 OK`):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "rt_9f8a7c6d...",
  "expires_in": 900,
  "server_time_utc": "2026-11-03T04:15:00Z",
  "must_change_password": true,
  "employee": {
    "employee_id": "8a1f3d90-2b4e-4b72-a111-9c002e4b1100",
    "tally_id": "1193",
    "name": "Shanto Miah",
    "role": "WORKER"
  }
}
```

**Behaviour & Errors:**
- If `must_change_password` is `true`, **only `POST /mobile/auth/change-password` is permitted**. Every other authenticated endpoint returns `403 PASSWORD_CHANGE_REQUIRED`.
- First successful login binds `device_identifier` to the employee. Any login from a different `device_identifier` returns `403 DEVICE_MISMATCH`.
- Errors: `401 INVALID_CREDENTIALS`, `403 DEVICE_MISMATCH`, `403 ACCOUNT_LOCKED` (default password expired or too many failures), `403 ACCOUNT_DISABLED`.

---

### 4.2 `POST /api/v1/mobile/auth/change-password`

Used in two cases:
1. **Mandatory first-login / post-reset change** (when `must_change_password` is `true`).
2. **Voluntary password change** initiated by the worker at any time from the Profile / Settings screen.

**Headers:** `Authorization: Bearer <access_token>`

**Request:**
```json
{
  "old_password": "TempPassword123!",
  "new_password": "MyNewSecurePassword9"
}
```

**Response (`200 OK`):**
```json
{
  "status": "PASSWORD_CHANGED",
  "server_time_utc": "2026-11-03T04:16:12Z"
}
```

**Validation Rules & Errors:**
- `new_password` must be **at least 8 characters** long (`PASSWORD_TOO_SHORT`).
- `new_password` **cannot equal the company default password** (`CANNOT_REUSE_DEFAULT_PASSWORD`).
- Wrong `old_password` returns `400 INVALID_CURRENT_PASSWORD`.
- On success, `must_change_password` becomes `false` and the existing access token immediately unlocks all other endpoints.

---

### 4.3 `POST /api/v1/mobile/auth/refresh`

**Request:**
```json
{
  "refresh_token": "rt_9f8a7c6d..."
}
```

**Response (`200 OK`):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "rt_new_rotated_token...",
  "expires_in": 900,
  "server_time_utc": "2026-11-03T04:30:00Z"
}
```
> Always persist the newly returned `refresh_token` immediately. Reusing a previously rotated refresh token revokes the session for security.

---

### 4.4 `POST /api/v1/mobile/auth/logout`

**Request:**
```json
{
  "refresh_token": "rt_new_rotated_token..."
}
```

**Response:** `204 No Content` (empty body). The client should clear tokens from the OS secure store and stop background tracking.

---

## 5. Location Policy & Consent Endpoints

### 5.1 `GET /api/v1/mobile/policy/location?language=en`

Fetches the latest active location tracking disclosure notice for the requested language (falls back to `en` if unavailable).

**Response (`200 OK`):**
```json
{
  "version": 1,
  "language": "en",
  "title": "Location Tracking Consent",
  "body": "Field Worker Tracker collects location data during working hours to verify worksite attendance.",
  "published_at": "2026-10-01T00:00:00Z"
}
```

- Show this notice before activating location tracking on first use.
- Whenever `SETTINGS.location_policy_version` is greater than the version the worker last accepted, display the updated policy and require re-acceptance via `POST /mobile/consent` before resuming tracking.

---

### 5.2 `POST /api/v1/mobile/consent`

Records that the worker accepted the displayed policy version. Also restores `consent_status` to `ACCEPTED` if the worker had previously withdrawn consent.

**Headers:** `Authorization: Bearer <access_token>`

**Request:**
```json
{
  "policy_version": 1,
  "consented_at_utc": "2026-11-03T04:17:00Z"
}
```

**Response (`200 OK`):**
```json
{
  "status": "CONSENT_RECORDED",
  "server_time_utc": "2026-11-03T04:17:01Z"
}
```

---

### 5.3 `POST /api/v1/mobile/consent/withdraw`

Allows a worker to withdraw location tracking consent (privacy compliance).

**Headers:** `Authorization: Bearer <access_token>`

**Request (body optional):**
```json
{
  "reason": "Prefer not to share location"
}
```

**Response (`200 OK`):**
```json
{
  "status": "CONSENT_WITHDRAWN",
  "server_time_utc": "2026-11-03T09:00:00Z"
}
```

**Server & Client Effect:**
- The server marks `employee.consent_status = "WITHDRAWN"`, logs an immutable withdrawal record, and raises an Admin alert.
- Any subsequent `POST /mobile/events/batch` returns `403 CONSENT_WITHDRAWN`.
- The app **must immediately stop collecting and uploading location events** until the worker re-consents via `POST /mobile/consent`.

---

## 6. Device Metadata & Heartbeat Endpoints

### 6.1 `PUT /api/v1/mobile/device`

Call whenever push token, app version, OS version, or location permission state changes. All fields are optional; send only changed or current values.

**Headers:** `Authorization: Bearer <access_token>`

**Request:**
```json
{
  "push_token": "fcm-updated-token-xyz",
  "app_version": "1.0.4",
  "os_version": "14.1",
  "location_permission_state": "ALWAYS"
}
```
- `location_permission_state`: `"ALWAYS"` | `"WHILE_IN_USE"` | `"DENIED"` | `"RESTRICTED"`.

**Response (`200 OK`):**
```json
{
  "status": "DEVICE_UPDATED",
  "server_time_utc": "2026-11-03T05:00:00Z"
}
```

---

### 6.2 `POST /api/v1/mobile/heartbeat`

Lightweight liveness & sync-check endpoint. Call:
- on app open,
- when network connectivity is restored,
- every 15 minutes while in the foreground, and
- **just before the morning alarm** so today's schedule and polygons are fresh.

**Headers:** `Authorization: Bearer <access_token>`

**Request:**
```json
{
  "app_version": "1.0.3",
  "cursor": 4812,
  "location_permission_state": "ALWAYS"
}
```

**Response (`200 OK`):**
```json
{
  "server_time_utc": "2026-11-03T04:25:00Z",
  "latest_cursor": 4815,
  "min_app_version": "1.0.0",
  "has_pending_changes": true
}
```
- If `has_pending_changes` is `true`, immediately call `GET /mobile/sync/changes?cursor=<local_cursor>`.
- If the app's version is below `min_app_version`, prompt the user to update the app.

---

## 7. Offline-First Synchronization

The server maintains an append-only, per-worker **change log** (`sync_changes`) keyed by a globally increasing integer `cursor`. The app stores the highest `cursor` it has **successfully committed** to local storage.

### 7.1 `GET /api/v1/mobile/sync/snapshot` (Initial Handshake & Full Resync)

Returns the worker's complete offline dataset for the next 30 days plus the `cursor` to resume incremental sync from. The server reads `cursor` **before** assembling entities so no concurrent change is missed (re-applying an already-included change is idempotent).

**Headers:** `Authorization: Bearer <access_token>`

**Response (`200 OK`):**
```json
{
  "cursor": 4812,
  "generated_at": "2026-11-03T04:18:00Z",
  "server_time_utc": "2026-11-03T04:18:00Z",
  "window": {
    "from": "2026-11-03",
    "to": "2026-12-03"
  },
  "entities": [
    {
      "entity_type": "PROFILE",
      "entity_id": "8a1f3d90-2b4e-4b72-a111-9c002e4b1100",
      "entity_version": 3,
      "payload": { "...see section 7.5..." }
    },
    {
      "entity_type": "SETTINGS",
      "entity_id": "self",
      "entity_version": 1,
      "payload": { "...see section 7.5..." }
    },
    {
      "entity_type": "ACCOMMODATION",
      "entity_id": "44c2e100-1122-4a89-99aa-001122334455",
      "entity_version": 1,
      "payload": { "...see section 7.5..." }
    },
    {
      "entity_type": "JOB",
      "entity_id": "77b10011-aaaa-4f22-b888-112233445566",
      "entity_version": 5,
      "payload": { "...see section 7.5..." }
    },
    {
      "entity_type": "SCHEDULE_ITEM",
      "entity_id": "99d31122-bbbb-4c33-a777-665544332211",
      "entity_version": 2,
      "payload": { "...see section 7.5..." }
    },
    {
      "entity_type": "DAY_PLAN",
      "entity_id": "2026-11-03",
      "entity_version": 1,
      "payload": { "...see section 7.5..." }
    }
  ]
}
```
> Note: The snapshot automatically merges both explicit daily operational assignments (`ScheduledWork`) and recurring master blueprint allocations (`BaseRosterTemplate`) across the 30-day window.

---

### 7.2 `GET /api/v1/mobile/sync/changes?cursor=4812&limit=200` (Incremental Delta Pull)

Query parameters:
- `cursor` (int, default `0`, `>= 0`): the last locally committed cursor.
- `limit` (int, default `200`, `1..500`): maximum change records per page.

**Headers:** `Authorization: Bearer <access_token>`

**Response (`200 OK`):**
```json
{
  "from_cursor": 4812,
  "next_cursor": 4815,
  "has_more": false,
  "resync_required": false,
  "server_time_utc": "2026-11-03T08:05:00Z",
  "changes": [
    {
      "cursor": 4813,
      "entity_type": "SCHEDULE_ITEM",
      "entity_id": "99d31122-bbbb-4c33-a777-665544332211",
      "entity_version": 3,
      "op": "UPSERT",
      "priority": 1,
      "payload": { "...full current entity JSON..." }
    },
    {
      "cursor": 4815,
      "entity_type": "SCHEDULE_ITEM",
      "entity_id": "aa112233-cccc-4d44-b666-778899001122",
      "entity_version": 4,
      "op": "DELETE",
      "priority": 1,
      "payload": null
    }
  ]
}
```

**Delta Rules:**
- `payload` on `UPSERT` is always the **full current entity snapshot** (never a partial diff).
- `op = "DELETE"` is a tombstone (`payload: null`): remove the matching `(entity_type, entity_id)` locally.
- **Coalescing:** The server may coalesce multiple changes to the same entity within a page, returning only the latest state. Do not depend on seeing intermediate versions.
- **`priority = 1`:** Indicates an urgent change affecting **today or tomorrow**. Apply immediately, and if shift tracking is active, immediately re-register today's geofence circles/polygons.
- **`resync_required = true`:** Occurs when the client's `cursor` is older than the server's 60-day compaction floor (`sync_cursor_floor`). Discard local synced entities, call `GET /mobile/sync/snapshot`, persist the snapshot, and ack the new cursor.
- **Pagination:** Loop calling `/mobile/sync/changes?cursor=<next_cursor>` while `has_more` is `true`.

---

### 7.3 `POST /api/v1/mobile/sync/ack`

Called **only after** the local database transaction applying a snapshot or delta page has committed. Powers the dashboard's device sync health (`Cursor Acked / Max` and sync lag).

**Headers:** `Authorization: Bearer <access_token>`

**Request:**
```json
{
  "cursor": 4815
}
```

**Response (`200 OK`):**
```json
{
  "status": "ACKNOWLEDGED",
  "last_acked_cursor": 4815,
  "server_time_utc": "2026-11-03T08:05:02Z"
}
```

**Client Transaction Rules:**
1. Apply all changes in a page inside **one atomic local DB transaction**:
   - On `UPSERT`: insert or overwrite `(entity_type, entity_id)` only if `entity_version >= stored_version`.
   - On `DELETE`: delete `(entity_type, entity_id)`.
2. Persist `next_cursor` only after the transaction commits. If it fails, do not advance the cursor.
3. Call `POST /mobile/sync/ack` with the committed cursor (idempotent; the server never moves `last_acked_cursor` backwards).
4. Purge local `SCHEDULE_ITEM` and `DAY_PLAN` rows older than 7 days; retain the 30-day forward window.

---

### 7.4 Silent Push Nudge (Optional Trigger)

When an urgent change occurs (e.g., reassignment or cancellation), the server may send a silent data push:
```json
{
  "type": "SYNC",
  "latest_cursor": 4815,
  "priority": 1
}
```
- Push payloads carry **no schedule or personal data** (privacy by design).
- On receipt, trigger `GET /mobile/sync/changes?cursor=<local_cursor>`.
- Because push delivery is best-effort, `POST /mobile/heartbeat` and app-open pulls remain the guaranteed fallback.

---

### 7.5 Synced Entity Payloads

#### `PROFILE` (`entity_id` = employee UUID)
```json
{
  "employee_id": "8a1f3d90-2b4e-4b72-a111-9c002e4b1100",
  "tally_id": "1193",
  "name": "Shanto Miah",
  "phone": "+971509876543",
  "email": "shanto.miah@company.com",
  "role": "WORKER",
  "staff_grade_description": "Gardener Expert",
  "weekly_off_day": "FRIDAY",
  "is_driver": false,
  "supervisor": {
    "name": "Shakir Khisham",
    "phone": "+971501234567"
  },
  "accommodation_id": "44c2e100-1122-4a89-99aa-001122334455"
}
```

#### `SETTINGS` (`entity_id` = `"self"`)
Static tracking configuration for this worker (effective-dated; `upcoming` lists scheduled changes within the 30-day window).
```json
{
  "geofence_circle_radius_m": 300,
  "approach_polling_interval_s": 60,
  "confirmation_polling_interval_s": 300,
  "schedule_sync_window_days": 30,
  "location_policy_version": 1,
  "alarm_lead_minutes": 30,
  "timezone": "Asia/Dubai",
  "upcoming": []
}
```
> Planned start/end times, break duration, standard hours, and alarm time are **not** in `SETTINGS` because they vary by season and time rules — always read them per date from `DAY_PLAN`.

#### `DAY_PLAN` (`entity_id` = `"YYYY-MM-DD"`)
One entity per date in the 30-day window, pre-resolved by the server from time rules, public holidays, weekly off, and approved leaves.
```json
{
  "work_date": "2026-11-03",
  "status": "WORKING",
  "marker": null,
  "planned": {
    "accommodation_start": "05:00",
    "first_site_start": "06:00",
    "break_minutes": 30,
    "last_site_end": "15:30",
    "accommodation_end": "17:00"
  },
  "standard_hours": 8,
  "alarm_time": "04:30"
}
```
- `status`:
  - `"WORKING"`: Normal scheduled workday (`planned` and `alarm_time` populated; ring morning alarm at `alarm_time`).
  - `"OFF"`: Weekly rest day (`marker: "WEEKLY_OFF"`, `planned: null`, `alarm_time: null`).
  - `"SKIPPED"`: Public holiday (`marker: "PUBLIC_HOLIDAY"`, `planned: null`, `alarm_time: null`).
  - `"LEAVE"`: Approved leave (`planned: null`, `alarm_time: null`).
  - `"FREE"`: Unscheduled day (`planned: null`, `alarm_time: null`).

#### `ACCOMMODATION` (`entity_id` = accommodation UUID)
Present when the worker is assigned to a company camp/accommodation.
```json
{
  "accommodation_id": "44c2e100-1122-4a89-99aa-001122334455",
  "name": "Warsan Labour Camp A",
  "address": "Warsan 2, Dubai",
  "boundary": {
    "type": "Polygon",
    "coordinates": [
      [
        [55.3800, 25.2900],
        [55.3810, 25.2900],
        [55.3810, 25.2910],
        [55.3800, 25.2910],
        [55.3800, 25.2900]
      ]
    ]
  },
  "geofence_circle": {
    "center": { "lat": 25.2905, "lng": 55.3805 },
    "radius_m": 420.5
  }
}
```

#### `JOB` (`entity_id` = worksite UUID)
One entity per unique worksite referenced by the worker's 30-day `SCHEDULE_ITEM` list.
```json
{
  "job_id": "77b10011-aaaa-4f22-b888-112233445566",
  "job_number": "12A14301",
  "job_name": "BKA - Wasl - R1068 Staff Accommodation, Warsan - Outdoor AMC",
  "boundary": {
    "type": "Polygon",
    "coordinates": [
      [
        [55.2000, 25.1000],
        [55.2015, 25.1000],
        [55.2015, 25.1012],
        [55.2000, 25.1012],
        [55.2000, 25.1000]
      ]
    ]
  },
  "geofence_circle": {
    "center": { "lat": 25.1006, "lng": 55.20075 },
    "radius_m": 480.0
  },
  "boundary_missing": false
}
```
- `geofence_circle` is computed server-side: centroid of the polygon with `radius_m = distance_to_farthest_vertex + geofence_circle_radius_m`.
- If `boundary_missing: true` (`boundary: null`, `geofence_circle: null`), display the site in the worker's schedule list **but do not register a geofence for it**.

#### `SCHEDULE_ITEM` (`entity_id` = scheduled work UUID)
```json
{
  "scheduled_work_id": "99d31122-bbbb-4c33-a777-665544332211",
  "work_date": "2026-11-03",
  "sequence": 1,
  "assigned_hours": 8.0,
  "status": "ACTIVE",
  "job_id": "77b10011-aaaa-4f22-b888-112233445566",
  "job_item_id": "55e42233-dddd-4a55-9876-1234567890ab",
  "sub_item_code": "A",
  "occurrence_id": "33f54455-eeee-4b66-8765-abcdef123456",
  "job_item_summary": {
    "staff_grade_description": "Gardener Expert",
    "deployment_type": "PERMANENT",
    "required_staff_count": 3,
    "hours_per_occurrence_per_staff": 8.0
  },
  "source": "PLANNED",
  "is_reassignment": false
}
```
- `status`: `ACTIVE`, `CANCELLED`, `REASSIGNED`, `COMPLETED`. Track and show `ACTIVE` items as today's active work; keep others only for history display.
- `source`: `PLANNED`, `REASSIGNMENT`, `COVER`, `FOLLOW_UP` (treat unknown values as `PLANNED`).
- `occurrence_id` may be `null` for recurring blueprint allocations that have not been materialized into individual daily overrides.

#### `TRANSPORT_RUN` *(Phase 10 schema — ignore if absent; handle when emitted)*
```json
{
  "run_id": "uuid",
  "run_date": "2026-11-03",
  "direction": "MORNING",
  "vehicle_label": "Hiace 04",
  "driver": { "name": "Shakir Khisham", "phone": "+971501234567" },
  "stops": [
    {
      "sequence": 1,
      "stop_type": "SITE",
      "job_id": "77b10011-aaaa-4f22-b888-112233445566",
      "name": "Wasl R1068",
      "planned_time": null,
      "passengers": [{ "name": "Shanto Miah", "tally_id": "1193" }]
    }
  ]
}
```
- Passengers see their driver, vehicle, and only their own stop/name. Drivers see all ordered stops and passenger manifests. Cancelled runs are removed via a `DELETE` tombstone.

#### Reserved Entity Types
Ignore until formally announced: `SCORECARD`, `PAYROLL_SUMMARY`.

---

## 8. Daily Tracking Behaviour & Event Upload Contract

### 8.1 On-Device Geofence & Polling State Machine
1. **Pre-shift sync & alarm:** Just before `DAY_PLAN.alarm_time` on `WORKING` days, call `POST /mobile/heartbeat` (and pull `/sync/changes` if pending). Ring the local wake-up alarm at `alarm_time`. When the worker dismisses the alarm, start shift tracking.
2. **Register OS geofence circles:** Register circular OS geofences using `geofence_circle` for:
   - the worker's `ACCOMMODATION` (if present — applies to **both workers and drivers** living in company camps so camp departure/return is captured), and
   - every `ACTIVE` worksite (`JOB`) scheduled for today where `boundary_missing == false`.
3. **Approach polling:** On entering a circle, begin polling GPS every `SETTINGS.approach_polling_interval_s` (default `60s`) and perform a local point-in-polygon check against the entity's `boundary`.
4. **Confirmed arrival (`ENTRY`):** Once a GPS fix falls inside the polygon, record an `ENTRY` event (with `job_id` for a worksite or `accommodation_id` for a camp). Switch to confirmation polling every `SETTINGS.confirmation_polling_interval_s` (default `300s`), recording each fix as a `PING`.
5. **Confirmed departure (`EXIT`):** When the OS geofence reports exit or a confirmation ping falls outside the polygon, poll to confirm the worker is outside, then record an `EXIT` event and stop high-frequency polling until the next circle entry.
6. **Location services toggled (`LOCATION_DISABLED` / `LOCATION_ENABLED`):**
   - If the worker turns off GPS / revokes permission during tracking: immediately record `LOCATION_DISABLED` using the **last known GPS fix** (`latitude`, `longitude`, and its earlier `location_captured_at_utc`; `latitude`/`longitude` may be `null` **only** if no fix was ever taken). Upload immediately as a single-event batch!
   - When GPS / permission is re-enabled: immediately record `LOCATION_ENABLED` with the fresh fix and upload immediately.
7. **Local notifications (app-side only):** Morning alarm reminders and arrival/departure confirmation notifications are raised locally by the app. Server-generated operational alerts (tracking interrupted, missed visits, document expiry) go only to Web / Supervisor / Engineer / Admin users.

---

### 8.2 `POST /api/v1/mobile/events/batch`

Uploads up to **200** raw location events in chronological order (oldest first).

**Headers:** `Authorization: Bearer <access_token>`

**Request:**
```json
{
  "batch_id": "1c9e8d70-4b3a-4f11-90aa-bbccddeeff01",
  "device_clock_utc": "2026-11-03T06:40:05Z",
  "events": [
    {
      "event_id": "a1b2c3d4-1111-4000-8000-000000000001",
      "event_type": "EXIT",
      "event_timestamp_utc": "2026-11-03T05:02:10Z",
      "location_captured_at_utc": "2026-11-03T05:02:10Z",
      "latitude": 25.2908,
      "longitude": 55.3812,
      "accuracy_meters": 10.2,
      "is_mock_location": false,
      "sequence_number": 209,
      "job_id": null,
      "job_item_id": null,
      "accommodation_id": "44c2e100-1122-4a89-99aa-001122334455"
    },
    {
      "event_id": "a1b2c3d4-1111-4000-8000-000000000002",
      "event_type": "ENTRY",
      "event_timestamp_utc": "2026-11-03T05:58:12Z",
      "location_captured_at_utc": "2026-11-03T05:58:12Z",
      "latitude": 25.1005,
      "longitude": 55.2007,
      "accuracy_meters": 12.5,
      "is_mock_location": false,
      "sequence_number": 210,
      "job_id": "77b10011-aaaa-4f22-b888-112233445566",
      "job_item_id": "55e42233-dddd-4a55-9876-1234567890ab",
      "accommodation_id": null
    },
    {
      "event_id": "a1b2c3d4-1111-4000-8000-000000000003",
      "event_type": "PING",
      "event_timestamp_utc": "2026-11-03T06:03:12Z",
      "location_captured_at_utc": "2026-11-03T06:03:12Z",
      "latitude": 25.1006,
      "longitude": 55.2008,
      "accuracy_meters": 9.8,
      "is_mock_location": false,
      "sequence_number": 211,
      "job_id": "77b10011-aaaa-4f22-b888-112233445566",
      "job_item_id": "55e42233-dddd-4a55-9876-1234567890ab",
      "accommodation_id": null
    }
  ]
}
```

**Field Rules:**
- `batch_id`: UUID generated per upload attempt.
- `device_clock_utc`: Device's current UTC time at the moment of upload (used to detect clock tampering/drift).
- `event_id`: UUID generated on the device **when the event is captured** and stored locally before upload. Guarantees idempotent retries.
- `event_type`: `ENTRY` | `EXIT` | `PING` | `LOCATION_DISABLED` | `LOCATION_ENABLED`.
- `event_timestamp_utc`: When the event occurred on the device.
- `location_captured_at_utc`: When the GPS fix was read (for `LOCATION_DISABLED`, this is the earlier timestamp of the last known fix).
- `latitude` / `longitude`: Required for `ENTRY`, `EXIT`, `PING` (`-90..90`, `-180..180`); nullable **only** for `LOCATION_DISABLED` when no prior fix exists.
- `accuracy_meters`, `is_mock_location`, `sequence_number`: Strongly recommended metadata used in server quality verification.
- `job_id`, `job_item_id`, `accommodation_id`: Optional client hints. **Employee identity is always derived from the Bearer token, never from the body.**

**Response (`200 OK`):**
```json
{
  "batch_id": "1c9e8d70-4b3a-4f11-90aa-bbccddeeff01",
  "server_time_utc": "2026-11-03T06:40:06Z",
  "latest_cursor": 4815,
  "results": [
    { "event_id": "a1b2c3d4-1111-4000-8000-000000000001", "status": "ACCEPTED", "reason": null },
    { "event_id": "a1b2c3d4-1111-4000-8000-000000000002", "status": "DUPLICATE", "reason": null },
    { "event_id": "a1b2c3d4-1111-4000-8000-000000000003", "status": "REJECTED", "reason": "TIMESTAMP_TOO_OLD" }
  ]
}
```

**Per-Event Status & Rejection Reasons:**
| `status` | Meaning | Required Client Action |
|---|---|---|
| `ACCEPTED` | Stored immutably & queued for verification/attendance. | Delete event from local outbox. |
| `DUPLICATE` | `event_id` was already accepted previously. | Treat as success; delete from local outbox. |
| `REJECTED` | Failed hard ingestion guard (`INVALID_SCHEMA`, `INVALID_COORDINATES`, `TIMESTAMP_IN_FUTURE` > +5 min, `TIMESTAMP_TOO_OLD` > 7 days). | Log locally for diagnostics & delete (do **not** retry). |

- **`latest_cursor`** is piggybacked on every batch response. If `latest_cursor > local_cursor`, immediately pull `/mobile/sync/changes`.
- **Whole-batch failures:**
  - Network timeout / `5xx`: Retry the **same** events (preserving original `event_id`s) with exponential backoff (5s up to 1h, with jitter).
  - `401 INVALID_TOKEN`: Refresh token via `/mobile/auth/refresh`, then retry.
  - `403 CONSENT_WITHDRAWN`: Stop tracking immediately and clear pending location uploads.
  - `403 DEVICE_NOT_ACTIVE`: Device was revoked by Admin — stop tracking and show re-registration screen.

---

### 8.3 How the Server Verifies Uploaded Events (Transparency for Mobile Team)

After an event is `ACCEPTED` into the immutable `mobile_event_logs` table, the backend synchronously runs spatial verification and attendance recalculation:

1. **Authoritative PostGIS Polygon Check:** The server ignores client trust and checks `(longitude, latitude)` against all active worksite and accommodation polygons stored in PostgreSQL/PostGIS (prioritizing today's assigned worksites when polygons overlap).
2. **Server Verdict (`server_verdict`):**
   - `INSIDE`: Point is strictly inside a stored polygon.
   - `BORDERLINE`: Point is outside the polygon, but `distance_to_polygon_m <= accuracy_meters`.
   - `OUTSIDE`: Point is outside the polygon beyond the GPS accuracy radius.
   - `NO_POLYGON`: Coordinates were null (e.g., initial `LOCATION_DISABLED`).
3. **Integrity & Quality Flags (`flags`):**
   - `CLOCK_SKEW`: `|server_received_at - device_clock_utc| > 120s` (default setting). Event timestamps are **never rewritten**; the skew is flagged for reviewer visibility.
   - `MOCK_LOCATION`: `is_mock_location == true`.
   - `LOW_ACCURACY`: `accuracy_meters > max_location_accuracy_m` (default `100m`).
   - `BORDERLINE_LOCATION`: Verdict fell in the accuracy margin.
   - `ENTRY_OUTSIDE_POLYGON`: Device logged `ENTRY`, but server verdict is `OUTSIDE`.
   - `EXIT_INSIDE_POLYGON`: Device logged `EXIT`, but server verdict is still `INSIDE`.
   - `UNKNOWN_SITE`: Point matched a site/camp without an assigned polygon match.
4. **Live Map & Alerts:** Accepted events immediately update the real-time live map (`EmployeeLastPosition`) and broadcast SSE updates. `LOCATION_DISABLED` immediately triggers an operational alert for the worker's supervisor and engineer; `LOCATION_ENABLED` resolves it.

---

## 9. On-Demand Endpoints (Not Synced)

### 9.1 `GET /api/v1/mobile/me/payroll` (Approved Monthly Payslips)

Returns only **employee-facing pay figures** from finalized (`APPROVED`) payroll runs. Employer costs, statutory provisions, and company overhead are strictly withheld.

#### A. List All Approved Payslips: `GET /api/v1/mobile/me/payroll`
Returns an array of `WorkerMobilePayrollSlip` sorted newest first (`[]` if no payroll runs have been approved yet).

**Headers:** `Authorization: Bearer <access_token>`

**Response (`200 OK`):**
```json
[
  {
    "payroll_record_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "payroll_run_id": "c9bf9e57-1685-4c89-bafb-ff5af830be8a",
    "month_label": "October 2026",
    "period": "2026-10",
    "period_start": "2026-10-01",
    "period_end": "2026-10-31",
    "status": "APPROVED",
    "employee_tally_id": "1193",
    "employee_name": "Shanto Miah",
    "currency": "AED",
    "standard_hours": "208.00",
    "worked_hours": "216.50",
    "paid_leave_hours": "0.00",
    "unpaid_leave_hours": "0.00",
    "salary_portion": "1100.00",
    "missed_hours_deduction": "0.00",
    "ot1_hours": "8.50",
    "ot1_amount": "56.19",
    "ot2_hours": "0.00",
    "ot2_amount": "0.00",
    "gross_pay": "1156.19",
    "net_pay": "1156.19",
    "approved_at": "2026-11-02T10:15:00Z"
  }
]
```

#### B. Fetch Single Month: `GET /api/v1/mobile/me/payroll?period=2026-10`
- Query param `period`: must match `^\d{4}-\d{2}$`.
- Returns a single `WorkerMobilePayrollSlip` JSON object (`200 OK`), or `404 PAYROLL_NOT_FOUND` if the month has not been approved yet.
- Monetary and hour values are serialized as exact decimal strings (e.g., `"1156.19"`). Parse as decimal/numeric for formatting; never apply float rounding logic to recompute totals.

### 9.2 Planned / Reserved Endpoints (Do Not Call Yet)
- `GET /api/v1/mobile/me/scorecard?week_start=YYYY-MM-DD` — *Reserved for a future contract update; not currently exposed by the backend.*

---

## 10. End-to-End Operational Scenarios

### Scenario 1: First-Time Onboarding & Device Binding
1. Worker opens app and enters `tally_id` + company default password.
2. App calls `POST /mobile/auth/login` with unique hardware `device_identifier`.
3. Server auto-provisions the user (if needed), binds the device, and returns `must_change_password: true`.
4. App forces the **Change Password** screen and submits `POST /mobile/auth/change-password`.
5. App fetches `GET /mobile/policy/location?language=en`, displays it, and submits `POST /mobile/consent` once accepted.
6. App calls `GET /mobile/sync/snapshot`, stores all entities + `cursor` in one local transaction, and calls `POST /mobile/sync/ack`.
7. App schedules tomorrow's wake-up alarm from `DAY_PLAN.alarm_time`.

### Scenario 2: Standard Working Day (Camp -> Site -> Camp)
1. 5 minutes before `DAY_PLAN.alarm_time` (`04:30`), app calls `POST /mobile/heartbeat`. If `has_pending_changes: true`, pulls `/sync/changes` and acks.
2. Alarm rings at `04:30`; worker dismisses alarm -> tracking starts.
3. App registers geofence circles for `ACCOMMODATION` (Warsan Camp) and today's `ACTIVE` `JOB`(s).
4. Worker leaves camp at `05:02` -> app emits `EXIT` (`accommodation_id` set) and queues/uploads it.
5. Worker approaches worksite circle at `05:54` -> approach polling every `60s` until inside polygon -> at `05:58`, emits `ENTRY` (`job_id` set) + local arrival notification.
6. Confirmation `PING` emitted every `300s` while inside polygon.
7. Worker leaves site at `15:32` -> confirms outside polygon -> emits `EXIT` (`job_id` set) + local departure notification.
8. Worker arrives back at camp at `16:45` -> emits `ENTRY` (`accommodation_id` set).
9. Server verifies every fix via PostGIS, builds visits, computes daily attendance (applying Supervisor Crew Break > Salary Structure Break > Company Default), and updates live map.

### Scenario 3: Offline Zone / Underground Basement
1. Worker enters a worksite with zero cellular connectivity.
2. App evaluates the locally cached `JOB.boundary` polygon and queues `ENTRY`, `PING`s, and `EXIT` with stable `event_id` UUIDs and original `event_timestamp_utc`.
3. Upload attempts fail with network timeout -> app backs off exponentially (5s -> 10s -> 20s ... up to 1h with jitter).
4. Hours later, signal returns -> app sends the queued batch (up to 200 events, oldest first) with current `device_clock_utc`.
5. Server accepts all events (within the 7-day age window), recalculates the affected `work_date`, and returns `ACCEPTED` (or `DUPLICATE` on retry). App deletes accepted/duplicate events from local queue.

### Scenario 4: Mid-Day Reassignment (`priority = 1`)
1. Supervisor reassigns worker to a different site at `10:00`.
2. Backend writes `UPSERT` (new `SCHEDULE_ITEM` + `JOB`) and `DELETE` (old `SCHEDULE_ITEM`) to `sync_changes` with `priority = 1`, and sends a silent `SYNC` push nudge.
3. App receives push (or discovers `has_pending_changes: true` on next heartbeat / batch response `latest_cursor`), pulls `GET /mobile/sync/changes`, applies transactionally, calls `POST /mobile/sync/ack`, and **immediately updates today's active geofence circles**.

### Scenario 5: Cursor Compaction & Full Resync
1. Worker's phone was off for >60 days; server compacted old `sync_changes` rows and updated `sync_cursor_floor`.
2. App calls `GET /mobile/sync/changes?cursor=100` -> server responds with `resync_required: true`.
3. App clears synced entity tables (never deleting unsent local outbox events), calls `GET /mobile/sync/snapshot`, commits new entities + cursor, and calls `POST /mobile/sync/ack`.

### Scenario 6: Worker Disables Location Services Mid-Shift
1. At `09:15`, worker turns off GPS.
2. App immediately creates a `LOCATION_DISABLED` event with `event_timestamp_utc = 09:15:00Z` and the last known coordinates + their earlier `location_captured_at_utc = 09:10:00Z`.
3. App uploads immediately via `POST /mobile/events/batch` (1-event batch) and calls `PUT /mobile/device` with `location_permission_state: "DENIED"`.
4. Server immediately raises a Tracking Interrupted alert for the supervisor and shows a warning on the Live Map.
5. At `09:30`, worker re-enables GPS -> app sends `LOCATION_ENABLED` immediately and updates `location_permission_state: "ALWAYS"`, auto-resolving the alert.

### Scenario 7: Worker Withdraws Tracking Consent
1. Worker taps "Withdraw Location Consent" in Profile/Privacy -> app calls `POST /mobile/consent/withdraw`.
2. App immediately stops geofencing and GPS polling.
3. If any stray event batch is sent, server rejects it with `403 CONSENT_WITHDRAWN`.
4. If the worker later opts back in, app displays `GET /mobile/policy/location`, calls `POST /mobile/consent`, and resumes normal tracking.

### Scenario 8: Device Swap or Admin Revocation
1. Worker gets a new phone and tries to log in -> server returns `403 DEVICE_MISMATCH`.
2. Admin revokes the old device from the Web Dashboard (`Devices` page).
3. Old phone receives `403 DEVICE_NOT_ACTIVE` on its next request -> stops tracking and returns to Login screen.
4. New phone logs in cleanly and becomes the newly bound device.

### Scenario 9: Voluntary Password Change & Admin Password Reset
- **Voluntary:** Worker opens Profile -> Change Password -> calls `POST /mobile/auth/change-password` with `{ old_password, new_password }`.
- **Admin Reset:** If worker forgets password, Admin resets it on Employee Detail -> backend sets company default password, clears lockouts, and sets `must_change_password = true`. Worker logs in with default password and is prompted to set a new password.

---

## 11. Complete Error Code Reference

| HTTP Status | Error `code` | Meaning | Required Mobile Action |
|---|---|---|---|
| `400` | `INVALID_CURRENT_PASSWORD` | `old_password` does not match on password change | Show validation error on current password field |
| `400` | `PASSWORD_TOO_SHORT` | `new_password` < 8 characters | Show validation error |
| `400` | `CANNOT_REUSE_DEFAULT_PASSWORD` | `new_password` equals company default password | Ask worker to choose a personal password |
| `400` | `NO_EMPLOYEE_LINKED` | Token belongs to a non-employee account | Show error; contact Admin |
| `401` | `UNAUTHENTICATED` / `INVALID_TOKEN` | Missing, malformed, or expired access token | Call `POST /mobile/auth/refresh`; if refresh fails, redirect to Login |
| `401` | `INVALID_CREDENTIALS` | Wrong `tally_id` or password on login | Show login error |
| `401` | `USER_NOT_FOUND` | Session user no longer exists | Clear session and redirect to Login |
| `403` | `PASSWORD_CHANGE_REQUIRED` | `must_change_password` is true | Lock navigation to Change Password screen |
| `403` | `DEVICE_MISMATCH` | Account is bound to a different phone | Show "Contact Admin to reset device binding" |
| `403` | `DEVICE_NOT_ACTIVE` | Current device was revoked by Admin | Stop tracking, clear session, show re-login message |
| `403` | `ACCOUNT_LOCKED` | Too many failures or default password expired | Show "Account locked — contact Admin" |
| `403` | `ACCOUNT_DISABLED` | Worker account deactivated | Stop tracking, clear session, show contact Admin |
| `403` | `CONSENT_WITHDRAWN` | Location batch rejected after consent withdrawal | Stop tracking immediately; prompt re-consent if worker wishes to resume |
| `404` | `PAYROLL_NOT_FOUND` | Requested `?period=YYYY-MM` has no approved slip | Show "Payslip not available yet for this month" |
| `404` | `EMPLOYEE_NOT_FOUND` | Linked employee record missing | Contact Admin |
| `422` | `VALIDATION_ERROR` | Malformed JSON body / missing required field | Fix payload schema; do not retry unchanged |
| `426` | `APP_VERSION_UNSUPPORTED` | App version below minimum supported | Force user to update app |
| `429` | `RATE_LIMITED` | Too many requests | Wait for `Retry-After` header before retrying |

---

## 12. Security Expectations for the Mobile App

- Store `access_token` and `refresh_token` exclusively in OS secure storage (Android Keystore / iOS Keychain).
- Never write tokens, passwords, or GPS coordinates to plaintext logs or crash analytics.
- Request only the OS permissions required for agreed features and always present `GET /mobile/policy/location` before activating GPS tracking.
- Never capture or transmit GPS coordinates when tracking is turned off or consent is withdrawn.

---

## 13. Sandbox, OpenAPI & Testing Tools Provided

- **Contract Doc:** `docs/09-mobile-sync-contract.md` (this file)
- **Machine-Readable OpenAPI Spec:** [`openapi-mobile.json`](../openapi-mobile.json)
- **Interactive Mobile Tester Proxy:** [`tools/mobile-proxy/`](../tools/mobile-proxy/) — browser-based simulator implementing login, forced/voluntary password change, consent, snapshot/delta sync, interactive geofence `ENTRY`/`PING`/`EXIT`/`LOCATION_DISABLED`/`LOCATION_ENABLED` emission (for both worksites and accommodation camps), and approved payslip viewing.
- **GPS Event Simulator:** [`tools/`](../tools/) scripts for generating realistic shift traces.

---

## 14. Contract Changelog

| Date | Version | Change |
|---|---|---|
| 2026-11-08 | 1.0.0 | Phase 07 baseline release: Auth (`/mobile/auth/login`, forced password change, single-device binding, mismatch handling), policy & consent (`/mobile/policy/location`, `/mobile/consent`), device & heartbeat (`/mobile/device`, `/mobile/heartbeat`), change log sync (`/mobile/sync/snapshot`, `/mobile/sync/changes` with delta coalescing, `/mobile/sync/ack`), location events batch ingestion (`/mobile/events/batch`), `openapi-mobile.json`, and standalone Mobile Tester Proxy (`tools/mobile-proxy/`). |
| 2026-11-15 | 1.0.1 | Added `GET /mobile/me/payroll` approved monthly payslip endpoint (Phase 11). |
| 2026-11-16 | 1.0.2 | Added `POST /mobile/consent/withdraw` and `403 CONSENT_WITHDRAWN` enforcement on event batch uploads (Phase 13). |
| 2026-11-17 | 1.1.0 | Added voluntary worker password change support on `POST /mobile/auth/change-password` and list-all-slips mode on `GET /mobile/me/payroll` (when `period` is omitted). |
| 2026-11-18 | 1.1.1 | Comprehensive contract audit & alignment with backend implementation: corrected `WorkerMobilePayrollSlip` response schema, documented `POST /mobile/consent/withdraw`, added request/response JSON examples for every endpoint, documented server-side PostGIS verification verdicts & flags, clarified password strength rules and planned `/mobile/me/scorecard` status, added complete error code table and 9 end-to-end operational scenarios. |
