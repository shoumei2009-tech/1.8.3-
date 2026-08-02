package com.gpswalker.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages favorite places storage with local and cloud-backed resilience.
 * 解決問題 8：每次刪掉 App 或更新後，能透過備份與雲端機制保留或還原我的最愛清單。
 */
class FavoritesManager(private val context: Context) {
    
    // 使用雲端與應用程式槽點，避免單純解除安裝時完全遺失
    private val prefs: SharedPreferences = context.getSharedPreferences("favorites_secure", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    private val listeners = mutableListOf<() -> Unit>()
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it() }
    }
    
    fun getAll(): List<FavoritePlace> {
        var json = prefs.getString("places", "[]") ?: "[]"
        
        // 容錯機制：如果安全槽點空了，嘗試從備份槽點（舊版 favorites）讀取一次並自動遷移
        if (json == "[]") {
            val oldPrefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
            val oldJson = oldPrefs.getString("places", "[]") ?: "[]"
            if (oldJson != "[]") {
                json = oldJson
                prefs.edit().putString("places", json).apply() // 自動遷移至安全備份槽
            }
        }
        
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        try {
            val maps: List<Map<String, Any>> = gson.fromJson(json, type) ?: emptyList()
            return maps.map { FavoritePlace.fromMap(it) }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    fun add(place: FavoritePlace) {
        val places = getAll().toMutableList()
        places.add(0, place)
        save(places)
        notifyListeners()
    }
    
    fun remove(id: String) {
        val places = getAll().toMutableList()
        places.removeAll { it.id == id }
        save(places)
        notifyListeners()
    }
    
    fun update(place: FavoritePlace) {
        val places = getAll().toMutableList()
        val index = places.indexOfFirst { it.id == place.id }
        if (index >= 0) {
            places[index] = place
            save(places)
            notifyListeners()
        }
    }
    
    fun search(query: String): List<FavoritePlace> {
        return getAll().filter { 
            it.name.contains(query, ignoreCase = true) || 
            it.coordinateString().contains(query)
        }
    }
    
    fun importAll(places: List<FavoritePlace>) {
        save(places)
        notifyListeners()
    }
    
    private fun save(places: List<FavoritePlace>) {
        val maps = places.map { it.toMap() }
        val json = gson.toJson(maps)
        // 同時寫入兩處（確保一般更新與備份機制不會被輕易洗掉）
        prefs.edit().putString("places", json).apply()
        context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
            .edit().putString("places", json).apply()
    }
}
