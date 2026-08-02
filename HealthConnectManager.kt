package com.gpswalker.app.util

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.units.Length
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Manages Health Connect integration for writing steps and distance data.
 * 優化批次寫入頻率，確保皮克敏 (Pikmin Bloom) 能完美讀取步數 - 解決問題 6
 */
class HealthConnectManager(private val context: Context) {
    
    private var client: HealthConnectClient? = null
    private var isAvailable = false
    
    private var fractionalSteps = 0.0
    
    // 【新增累積緩衝區，改為每 5 秒批次寫入一次，確保皮克敏和 Google Fit 能順利讀取】
    private var accumulatedSteps = 0L
    private var accumulatedDistance = 0.0
    private var lastBatchWriteTime = System.currentTimeMillis()
    
    enum class ConnectionStatus {
        CONNECTED, NOT_AUTHORIZED, UNAVAILABLE, ERROR
    }
    
    val permissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class)
    )
    
    fun getPermissionContract() = PermissionController.createRequestPermissionResultContract()
    
    fun initialize(): ConnectionStatus {
        return try {
            val availability = HealthConnectClient.getSdkStatus(context)
            if (availability == HealthConnectClient.SDK_AVAILABLE) {
                client = HealthConnectClient.getOrCreate(context)
                isAvailable = true
                ConnectionStatus.CONNECTED
            } else {
                ConnectionStatus.UNAVAILABLE
            }
        } catch (e: Exception) {
            ConnectionStatus.ERROR
        }
    }
    
    suspend fun checkPermissions(): Boolean = withContext(Dispatchers.IO) {
        try {
            val granted = client?.permissionController?.getGrantedPermissions() ?: emptySet()
            permissions.all { it in granted }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 接收每秒的數據，累積後每 5 秒批次寫入 Health Connect，讓皮克敏順利同步
     */
    suspend fun writeSecondData(distanceMeters: Double, stepLength: Double = 0.75): Boolean = withContext(Dispatchers.IO) {
        try {
            val hcClient = client ?: return@withContext false
            
            val rawSteps = distanceMeters / stepLength
            fractionalSteps += rawSteps
            val wholeSteps = fractionalSteps.toLong()
            fractionalSteps -= wholeSteps
            
            // 累積步數與距離
            accumulatedSteps += wholeSteps
            accumulatedDistance += distanceMeters
            
            val nowMs = System.currentTimeMillis()
            // 每 5 秒（5000毫秒）寫入一次資料庫，避免過於頻繁被忽略
            if (nowMs - lastBatchWriteTime < 5000) {
                return@withContext true
            }
            
            if (accumulatedSteps <= 0 && accumulatedDistance < 0.01) {
                lastBatchWriteTime = nowMs
                return@withContext true
            }
            
            val stepsToWrite = accumulatedSteps
            val distanceToWrite = accumulatedDistance
            
            accumulatedSteps = 0
            accumulatedDistance = 0.0
            val startMs = lastBatchWriteTime
            lastBatchWriteTime = nowMs
            
            val endTime = Instant.ofEpochMilli(nowMs)
            val startTime = Instant.ofEpochMilli(startMs)
            val zoneOffset = ZoneId.systemDefault().rules.getOffset(endTime)
            
            val records = mutableListOf<androidx.health.connect.client.records.Record>()
            
            if (stepsToWrite > 0) {
                records.add(
                    StepsRecord(
                        count = stepsToWrite,
                        startTime = startTime,
                        endTime = endTime,
                        startZoneOffset = zoneOffset,
                        endZoneOffset = zoneOffset
                    )
                )
            }
            
            if (distanceToWrite > 0.01) {
                records.add(
                    DistanceRecord(
                        distance = Length.meters(distanceToWrite),
                        startTime = startTime,
                        endTime = endTime,
                        startZoneOffset = zoneOffset,
                        endZoneOffset = zoneOffset
                    )
                )
            }
            
            if (records.isNotEmpty()) {
                hcClient.insertRecords(records)
            }
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "writeSecondData failed: ${e.message}", e)
            false
        }
    }
    
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val hcClient = client ?: return@withContext false
            val now = Instant.now()
            val testRecord = StepsRecord(
                count = 1,
                startTime = now.minusSeconds(1),
                endTime = now,
                startZoneOffset = ZoneId.systemDefault().rules.getOffset(now),
                endZoneOffset = ZoneId.systemDefault().rules.getOffset(now)
            )
            hcClient.insertRecords(listOf(testRecord))
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "testConnection failed: ${e.message}", e)
            false
        }
    }
    
    fun resetFractionalSteps() {
        fractionalSteps = 0.0
        accumulatedSteps = 0
        accumulatedDistance = 0.0
        lastBatchWriteTime = System.currentTimeMillis()
    }
}
