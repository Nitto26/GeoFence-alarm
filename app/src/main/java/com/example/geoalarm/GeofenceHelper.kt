package com.example.geoalarm

import android.location.Location
import com.example.geoalarm.network.AccommodationItem
import com.example.geoalarm.network.JobItem
import com.example.geoalarm.network.LocationCoordinate

object GeofenceHelper {

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
     * Determines whether (lat, lng) is within an active worksite boundary.
     * Polygon: inside polygon OR within 40m edge buffer.
     * Point: within 75m circular radius.
     */
    fun isCoordinateInsideJob(lat: Double, lng: Double, job: JobItem): Boolean {
        if (job.location.isEmpty()) return false
        if (job.siteType.equals("accommodation", ignoreCase = true) || job.isStartingPoint == true) {
            return false
        }

        val points = job.location
        return if (points.size >= 3) {
            if (isPointInPolygon(lat, lng, points)) return true
            minDistanceToPoints(lat, lng, points) <= 40f
        } else {
            minDistanceToPoints(lat, lng, points) <= 75f
        }
    }

    /**
     * Returns the active worksite if the worker is inside any assigned job.
     */
    fun findMatchingJob(lat: Double, lng: Double, jobs: List<JobItem>): JobItem? {
        for (job in jobs) {
            if (isCoordinateInsideJob(lat, lng, job)) {
                return job
            }
        }
        return null
    }

    /**
     * Determines whether (lat, lng) is strictly within Accommodation/Stay.
     * Uses tight polygon containment (15m buffer) or 25m radius if point.
     */
    fun isCoordinateInsideAccommodation(lat: Double, lng: Double, acc: AccommodationItem?): Boolean {
        if (acc == null || acc.location.isEmpty()) return false
        val points = acc.location
        return if (points.size >= 3) {
            if (isPointInPolygon(lat, lng, points)) return true
            minDistanceToPoints(lat, lng, points) <= 15f
        } else {
            minDistanceToPoints(lat, lng, points) <= 25f
        }
    }

    /**
     * Returns the minimum distance in meters to the accommodation boundary/center.
     */
    fun getDistanceToAccommodation(lat: Double, lng: Double, acc: AccommodationItem?): Float {
        if (acc == null || acc.location.isEmpty()) return Float.MAX_VALUE
        return minDistanceToPoints(lat, lng, acc.location)
    }
}
