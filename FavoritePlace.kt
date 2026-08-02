package com.gpswalker.app.data.local

import java.util.UUID

/**
 * Data class representing a favorite place.
 */
data class FavoritePlace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "latitude" to latitude,
        "longitude" to longitude,
        "createdAt" to createdAt
    )
    
    companion object {
        fun fromMap(map: Map<String, Any>): FavoritePlace {
            return FavoritePlace(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                name = map["name"] as? String ?: "未命名地點",
                latitude = (map["latitude"] as? Number)?.toDouble() ?: 0.0,
                longitude = (map["longitude"] as? Number)?.toDouble() ?: 0.0,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
    
    fun coordinateString(): String = String.format("%.6f, %.6f", latitude, longitude)
}
