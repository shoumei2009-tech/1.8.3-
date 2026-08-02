package com.gpswalker.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Geocoding helper using free Nominatim (OpenStreetMap) API.
 * Supports Chinese place name search and reverse geocoding.
 */
class GeocodingHelper {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    data class GeoResult(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double
    )
    
    data class NominatimResult(
        @SerializedName("display_name") val displayName: String?,
        @SerializedName("lat") val lat: String?,
        @SerializedName("lon") val lon: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("address") val address: NominatimAddress?
    )
    
    data class NominatimAddress(
        @SerializedName("road") val road: String?,
        @SerializedName("city") val city: String?,
        @SerializedName("state") val state: String?,
        @SerializedName("country") val country: String?
    )
    
    /**
     * Search for a place by name (supports Chinese).
     */
    suspend fun searchPlace(query: String): List<GeoResult> = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=5&accept-language=zh-TW"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GPSWalker/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            
            val results = gson.fromJson(body, Array<NominatimResult>::class.java)
            results.mapNotNull { result ->
                val lat = result.lat?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = result.lon?.toDoubleOrNull() ?: return@mapNotNull null
                GeoResult(
                    name = result.name ?: result.displayName?.split(",")?.firstOrNull() ?: query,
                    address = result.displayName ?: "",
                    latitude = lat,
                    longitude = lon
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Reverse geocode: coordinates to place name.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): GeoResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&accept-language=zh-TW"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GPSWalker/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            
            val result = gson.fromJson(body, NominatimResult::class.java)
            GeoResult(
                name = result.name ?: result.displayName?.split(",")?.firstOrNull() ?: "未知地點",
                address = result.displayName ?: "",
                latitude = lat,
                longitude = lng
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Detect if input is a coordinate pair.
     * Supports formats: "25.033964, 121.564468" or "25.033964 121.564468"
     */
    fun parseCoordinates(input: String): Pair<Double, Double>? {
        val cleaned = input.trim()
        val patterns = listOf(
            Regex("""(-?\d+\.?\d*)\s*[,，]\s*(-?\d+\.?\d*)"""),
            Regex("""(-?\d+\.?\d*)\s+(-?\d+\.?\d*)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.matchEntire(cleaned)
            if (match != null) {
                val lat = match.groupValues[1].toDoubleOrNull() ?: continue
                val lng = match.groupValues[2].toDoubleOrNull() ?: continue
                if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                    return Pair(lat, lng)
                }
            }
        }
        return null
    }
}
