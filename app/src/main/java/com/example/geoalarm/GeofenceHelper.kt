package com.example.geoalarm

import android.location.Location
import com.example.geoalarm.network.AccommodationItem
import com.example.geoalarm.network.JobItem
import com.example.geoalarm.network.LocationCoordinate

object GeofenceHelper {

    const val DEFAULT_MAX_ACCURACY_METERS = 15f
    const val PINPOINT_PERIMETER_BUFFER_METERS = 5f // Pin-to-pin 5m high accuracy boundary tolerance

    /**
     * Standard Ray-Casting algorithm to determine if a point is inside a polygon.
     */
    fun isPointInPolygon(lat: Double, lng: Double, points: List<LocationCoordinate>): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val pi = points[i]
            val pj = points[j]
            if ((pi.longitude > lng) != (pj.longitude > lng) &&
                lat < (pj.latitude - pi.latitude) * (lng - pi.longitude) / (pj.longitude - pi.longitude) + pi.latitude
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Checks whether a GPS location reading has acceptable accuracy.
     * Rejects erratic cell-tower / WiFi multipath jumps (e.g. 40m - 100m error)
     * when phone is resting indoors.
     */
    fun isLocationAccurate(location: Location?, maxAccuracyMeters: Float = DEFAULT_MAX_ACCURACY_METERS): Boolean {
        if (location == null) return false
        if (!location.hasAccuracy()) return true
        return location.accuracy <= maxAccuracyMeters
    }

    /**
     * Calculates direct distance in meters between two lat/lng points.
     */
    fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        return result[0]
    }

    /**
     * Finds the minimum distance from (lat, lng) to any vertex in the coordinate list.
     */
    fun minDistanceToPoints(lat: Double, lng: Double, points: List<LocationCoordinate>): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        var minDist = Float.MAX_VALUE
        for (pt in points) {
            val d = distanceBetween(lat, lng, pt.latitude, pt.longitude)
            if (d < minDist) {
                minDist = d
            }
        }
        return minDist
    }

    /**
     * Calculates minimum perpendicular distance to any segment of the polygon.
     */
    fun minDistanceToPolygonPerimeter(lat: Double, lng: Double, points: List<LocationCoordinate>): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size < 3) return minDistanceToPoints(lat, lng, points)

        var minDistance = Float.MAX_VALUE
        var j = points.size - 1
        for (i in points.indices) {
            val d = distanceToSegment(lat, lng, points[j].latitude, points[j].longitude, points[i].latitude, points[i].longitude)
            if (d < minDistance) {
                minDistance = d
            }
            j = i
        }
        return minDistance
    }

    private fun distanceToSegment(pLat: Double, pLng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val abDist = distanceBetween(aLat, aLng, bLat, bLng)
        if (abDist == 0f) return distanceBetween(pLat, pLng, aLat, aLng)

        // Project point p onto segment ab
        val apDist = distanceBetween(aLat, aLng, pLat, pLng)
        val bpDist = distanceBetween(bLat, bLng, pLat, pLng)

        // If angle is obtuse, return distance to nearest endpoint
        if (apDist * apDist >= bpDist * bpDist + abDist * abDist) return bpDist
        if (bpDist * bpDist >= apDist * apDist + abDist * abDist) return apDist

        // Perpendicular distance using Heron's formula / triangle height
        val s = (abDist + apDist + bpDist) / 2f
        val area = Math.sqrt(Math.max(0.0, (s * (s - abDist) * (s - apDist) * (s - bpDist)).toDouble())).toFloat()
        return (2f * area) / abDist
    }

    /**
     * Determines whether (lat, lng) is within an active worksite boundary.
     * Polygon: strictly inside polygon OR within pinpoint 10m perimeter buffer.
     * Point: within 35m circular radius.
     */
    fun isCoordinateInsideJob(
        lat: Double,
        lng: Double,
        job: JobItem,
        perimeterBuffer: Float = PINPOINT_PERIMETER_BUFFER_METERS
    ): Boolean {
        if (job.location.isEmpty()) return false
        if (job.siteType.equals("accommodation", ignoreCase = true) || job.isStartingPoint == true) {
            return false
        }

        val points = job.location
        return if (points.size >= 3) {
            if (isPointInPolygon(lat, lng, points)) return true
            minDistanceToPolygonPerimeter(lat, lng, points) <= perimeterBuffer
        } else {
            minDistanceToPoints(lat, lng, points) <= 35f
        }
    }

    /**
     * Returns the active worksite if the worker is inside any assigned job.
     */
    fun findMatchingJob(
        lat: Double,
        lng: Double,
        jobs: List<JobItem>,
        perimeterBuffer: Float = PINPOINT_PERIMETER_BUFFER_METERS
    ): JobItem? {
        for (job in jobs) {
            if (isCoordinateInsideJob(lat, lng, job, perimeterBuffer)) {
                return job
            }
        }
        return null
    }

    /**
     * Determines whether (lat, lng) is strictly within Accommodation/Stay.
     * Uses tight polygon containment (10m buffer) or 20m radius if point.
     */
    fun isCoordinateInsideAccommodation(lat: Double, lng: Double, acc: AccommodationItem?): Boolean {
        if (acc == null || acc.location.isEmpty()) return false
        val points = acc.location
        return if (points.size >= 3) {
            if (isPointInPolygon(lat, lng, points)) return true
            minDistanceToPolygonPerimeter(lat, lng, points) <= 10f
        } else {
            minDistanceToPoints(lat, lng, points) <= 20f
        }
    }

    /**
     * Returns the minimum distance in meters to the accommodation boundary/center.
     */
    fun getDistanceToAccommodation(lat: Double, lng: Double, acc: AccommodationItem?): Float {
        if (acc == null || acc.location.isEmpty()) return Float.MAX_VALUE
        return minDistanceToPolygonPerimeter(lat, lng, acc.location)
    }
}
