package com.gpswalker.app.util

import kotlin.math.*
import kotlin.random.Random

/**
 * Movement engine that generates smooth random curved paths within a defined zone.
 * Uses Perlin-like noise for natural movement at 20 km/h.
 */
class MovementEngine(
    private var centerLat: Double,
    private var centerLng: Double,
    private val radiusMeters: Double,
    private val shape: ZoneShape = ZoneShape.CIRCLE,
    private val speedKmh: Double = 20.0
) {
    enum class ZoneShape { CIRCLE, SQUARE, HEXAGON }
    
    private var currentLat = centerLat
    private var currentLng = centerLng
    private var direction = Random.nextDouble() * 2 * PI
    private var noiseOffset = Random.nextDouble() * 1000.0
    private val speedMps = speedKmh / 3.6 // meters per second
    
    // Perlin noise state
    private var t = 0.0
    
    data class Position(val latitude: Double, val longitude: Double)
    
    fun getCurrentPosition(): Position = Position(currentLat, currentLng)
    
    /**
     * Update center position (for teleport functionality)
     */
    fun updateCenter(newLat: Double, newLng: Double) {
        centerLat = newLat
        centerLng = newLng
        currentLat = newLat
        currentLng = newLng
    }
    
    /**
     * Advance the position by deltaSeconds.
     * Returns the new position.
     */
    fun advance(deltaSeconds: Double = 1.0): Position {
        t += deltaSeconds * 0.1
        
        // Perlin-like smooth direction change
        val noiseVal = smoothNoise(t + noiseOffset)
        direction += noiseVal * 0.3 * deltaSeconds
        
        // Calculate distance to boundary
        val distFromCenter = haversineDistance(currentLat, currentLng, centerLat, centerLng)
        val boundaryRatio = distFromCenter / radiusMeters
        
        // Boundary repulsion - steer back toward center when near edge
        if (boundaryRatio > 0.7) {
            val bearingToCenter = bearing(currentLat, currentLng, centerLat, centerLng)
            val angleDiff = normalizeAngle(bearingToCenter - direction)
            val repulsionStrength = ((boundaryRatio - 0.7) / 0.3).coerceIn(0.0, 1.0)
            direction += angleDiff * repulsionStrength * 0.5 * deltaSeconds
        }
        
        // Add slight speed variation (±5%)
        val actualSpeed = speedMps * (1.0 + (smoothNoise(t * 2 + 100) * 0.05))
        val distance = actualSpeed * deltaSeconds
        
        // Move in current direction
        val newPos = movePoint(currentLat, currentLng, direction, distance)
        
        // Verify within bounds, if not, reflect
        if (isWithinZone(newPos.latitude, newPos.longitude)) {
            currentLat = newPos.latitude
            currentLng = newPos.longitude
        } else {
            // Reflect direction and try again
            direction += PI * 0.7
            val reflectedPos = movePoint(currentLat, currentLng, direction, distance * 0.5)
            if (isWithinZone(reflectedPos.latitude, reflectedPos.longitude)) {
                currentLat = reflectedPos.latitude
                currentLng = reflectedPos.longitude
            } else {
                // Force move toward center to prevent getting stuck
                direction = bearing(currentLat, currentLng, centerLat, centerLng)
                val safePos = movePoint(currentLat, currentLng, direction, distance * 0.3)
                currentLat = safePos.latitude
                currentLng = safePos.longitude
            }
        }
        
        // Add GPS jitter (±2 meters)
        val jitterLat = (Random.nextDouble() - 0.5) * 0.000018
        val jitterLng = (Random.nextDouble() - 0.5) * 0.000018
        
        return Position(currentLat + jitterLat, currentLng + jitterLng)
    }
    
    fun isWithinZone(lat: Double, lng: Double): Boolean {
        return when (shape) {
            ZoneShape.CIRCLE -> {
                haversineDistance(lat, lng, centerLat, centerLng) <= radiusMeters
            }
            ZoneShape.SQUARE -> {
                val dLat = abs(lat - centerLat) * 111320
                val dLng = abs(lng - centerLng) * 111320 * cos(Math.toRadians(centerLat))
                dLat <= radiusMeters && dLng <= radiusMeters
            }
            ZoneShape.HEXAGON -> {
                val dx = abs(lng - centerLng) * 111320 * cos(Math.toRadians(centerLat))
                val dy = abs(lat - centerLat) * 111320
                // Hexagon check
                dx <= radiusMeters * 0.866 && dy <= radiusMeters &&
                    (dx * 0.5 + dy * 0.866) <= radiusMeters * 0.866
            }
        }
    }
    
    private fun smoothNoise(x: Double): Double {
        val xi = x.toInt()
        val xf = x - xi
        val u = xf * xf * (3 - 2 * xf) // smoothstep
        val a = pseudoRandom(xi)
        val b = pseudoRandom(xi + 1)
        return a + u * (b - a)
    }
    
    private fun pseudoRandom(x: Int): Double {
        val n = x * 1619 + x * 31337
        return (sin(n.toDouble()) * 43758.5453).let { it - floor(it) } * 2 - 1
    }
    
    companion object {
        fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val R = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
        
        fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLng = Math.toRadians(lng2 - lng1)
            val lat1R = Math.toRadians(lat1)
            val lat2R = Math.toRadians(lat2)
            val y = sin(dLng) * cos(lat2R)
            val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLng)
            return atan2(y, x)
        }
        
        fun movePoint(lat: Double, lng: Double, bearingRad: Double, distanceMeters: Double): Position {
            val R = 6371000.0
            val latR = Math.toRadians(lat)
            val lngR = Math.toRadians(lng)
            val d = distanceMeters / R
            
            val newLat = asin(sin(latR) * cos(d) + cos(latR) * sin(d) * cos(bearingRad))
            val newLng = lngR + atan2(
                sin(bearingRad) * sin(d) * cos(latR),
                cos(d) - sin(latR) * sin(newLat)
            )
            
            return Position(Math.toDegrees(newLat), Math.toDegrees(newLng))
        }
        
        fun normalizeAngle(angle: Double): Double {
            var a = angle
            while (a > PI) a -= 2 * PI
            while (a < -PI) a += 2 * PI
            return a
        }
    }
}
