package com.gpswalker.app.util

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 雲端直寫步數管理器
 *
 * 原理與 DeFit 相同：透過 Google Fitness REST API 直接把步數寫進
 * 使用者 Google 帳號的雲端健身資料庫，皮克敏等 Niantic 遊戲會從雲端
 * 讀取步數，因此不需要經過 Google Fit App 的背景同步。
 */
object CloudFitnessManager {

    private const val TAG = "CloudFitness"

    const val SCOPE_ACTIVITY_WRITE = "https://www.googleapis.com/auth/fitness.activity.write"
    const val SCOPE_LOCATION_WRITE = "https://www.googleapis.com/auth/fitness.location.write"
    private const val OAUTH_SCOPE =
        "oauth2:$SCOPE_ACTIVITY_WRITE $SCOPE_LOCATION_WRITE"

    private const val BASE_URL = "https://fitness.googleapis.com/fitness/v1/users/me"

    // 步數資料來源（dataStreamId 由 type:dataType:packageName:streamName 組成）
    private const val STEP_STREAM_NAME = "GPSWalkerSteps"
    private const val DISTANCE_STREAM_NAME = "GPSWalkerDistance"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var stepDataStreamId: String? = null

    @Volatile
    private var distanceDataStreamId: String? = null

    /** 最近一次上傳結果描述（給 UI 顯示） */
    @Volatile
    var lastUploadStatus: String = "尚未上傳"
        private set

    /** 累計已上傳到雲端的步數（本次服務執行期間） */
    @Volatile
    var totalUploadedSteps: Long = 0
        private set

    fun getSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_ACTIVITY_WRITE), Scope(SCOPE_LOCATION_WRITE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val hasScope = GoogleSignIn.hasPermissions(
            account, Scope(SCOPE_ACTIVITY_WRITE), Scope(SCOPE_LOCATION_WRITE)
        )
        return if (hasScope) account else null
    }

    fun isSignedIn(context: Context): Boolean = getSignedInAccount(context) != null

    fun signOut(context: Context) {
        getSignInClient(context).signOut()
        cachedToken = null
        stepDataStreamId = null
        distanceDataStreamId = null
        totalUploadedSteps = 0
        lastUploadStatus = "已登出"
    }

    /**
     * 取得 OAuth access token。
     * 若需要使用者同意，會拋出 UserRecoverableAuthException，
     * 呼叫端應捕捉並用 exception.intent 引導使用者授權。
     */
    private suspend fun getToken(context: Context, account: Account): String =
        withContext(Dispatchers.IO) {
            cachedToken ?: GoogleAuthUtil.getToken(context, account, OAUTH_SCOPE)
                .also { cachedToken = it }
        }

    private suspend fun clearToken(context: Context) = withContext(Dispatchers.IO) {
        cachedToken?.let {
            try {
                GoogleAuthUtil.clearToken(context, it)
            } catch (e: Exception) {
                Log.w(TAG, "clearToken failed", e)
            }
        }
        cachedToken = null
    }

    /**
     * 確保雲端 dataSource 存在，回傳 dataStreamId。
     */
    private suspend fun ensureDataSource(
        context: Context,
        account: Account,
        dataTypeName: String,
        fieldName: String,
        fieldFormat: String,
        streamName: String
    ): String = withContext(Dispatchers.IO) {
        val token = getToken(context, account)
        val body = JSONObject().apply {
            put("dataStreamName", streamName)
            put("type", "raw")
            put("application", JSONObject().apply {
                put("name", "GPS Walker")
                put("version", "1")
            })
            put("dataType", JSONObject().apply {
                put("name", dataTypeName)
                put("field", JSONArray().put(JSONObject().apply {
                    put("name", fieldName)
                    put("format", fieldFormat)
                }))
            })
        }
        val request = Request.Builder()
            .url("$BASE_URL/dataSources")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            when {
                resp.isSuccessful -> {
                    val id = JSONObject(respBody).getString("dataStreamId")
                    Log.i(TAG, "dataSource created: $id")
                    id
                }
                resp.code == 409 -> {
                    // 已存在，從錯誤訊息或列表取回 id
                    val id = findExistingDataStreamId(token, dataTypeName, streamName)
                        ?: "raw:$dataTypeName:${context.packageName}:$streamName"
                    Log.i(TAG, "dataSource exists: $id")
                    id
                }
                resp.code == 401 -> {
                    clearToken(context)
                    throw Exception("授權過期(401)，請重試")
                }
                else -> throw Exception("建立資料來源失敗 HTTP ${resp.code}: $respBody")
            }
        }
    }

    private fun findExistingDataStreamId(
        token: String,
        dataTypeName: String,
        streamName: String
    ): String? {
        val request = Request.Builder()
            .url("$BASE_URL/dataSources?dataTypeName=$dataTypeName")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONObject(resp.body?.string() ?: "{}")
                .optJSONArray("dataSource") ?: return null
            for (i in 0 until arr.length()) {
                val ds = arr.getJSONObject(i)
                if (ds.optString("dataStreamName") == streamName) {
                    return ds.getString("dataStreamId")
                }
            }
        }
        return null
    }

    /**
     * 上傳一段時間內的步數（以及對應距離）到 Google 雲端。
     *
     * @param steps 該時段的步數
     * @param distanceMeters 該時段的距離（公尺），<=0 則不上傳距離
     * @param startMillis 時段開始時間
     * @param endMillis 時段結束時間
     * @return true 表示成功
     */
    suspend fun uploadSteps(
        context: Context,
        steps: Int,
        distanceMeters: Double,
        startMillis: Long,
        endMillis: Long
    ): Boolean {
        if (steps <= 0) return true
        val account = getSignedInAccount(context)?.account ?: run {
            lastUploadStatus = "未登入 Google 帳號"
            return false
        }
        return try {
            // 步數
            val stepId = stepDataStreamId ?: ensureDataSource(
                context, account,
                "com.google.step_count.delta", "steps", "integer", STEP_STREAM_NAME
            ).also { stepDataStreamId = it }
            patchDataset(
                context, account, stepId,
                "com.google.step_count.delta",
                startMillis, endMillis
            ) { point ->
                point.put("value", JSONArray().put(JSONObject().put("intVal", steps)))
            }

            // 距離（可選）
            if (distanceMeters > 0) {
                try {
                    val distId = distanceDataStreamId ?: ensureDataSource(
                        context, account,
                        "com.google.distance.delta", "distance", "floatPoint",
                        DISTANCE_STREAM_NAME
                    ).also { distanceDataStreamId = it }
                    patchDataset(
                        context, account, distId,
                        "com.google.distance.delta",
                        startMillis, endMillis
                    ) { point ->
                        point.put(
                            "value",
                            JSONArray().put(JSONObject().put("fpVal", distanceMeters))
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "distance upload failed (ignored)", e)
                }
            }

            totalUploadedSteps += steps
            lastUploadStatus = "上次上傳成功（累計 $totalUploadedSteps 步）"
            Log.i(TAG, "uploaded $steps steps to cloud")
            true
        } catch (e: UserRecoverableAuthException) {
            lastUploadStatus = "需要重新授權 Google 帳號"
            Log.w(TAG, "user recoverable auth needed", e)
            false
        } catch (e: Exception) {
            lastUploadStatus = "上傳失敗：${e.message?.take(80)}"
            Log.e(TAG, "upload failed", e)
            false
        }
    }

    private suspend fun patchDataset(
        context: Context,
        account: Account,
        dataStreamId: String,
        dataTypeName: String,
        startMillis: Long,
        endMillis: Long,
        valueBuilder: (JSONObject) -> Unit
    ) = withContext(Dispatchers.IO) {
        val token = getToken(context, account)
        val startNs = startMillis * 1_000_000
        val endNs = endMillis * 1_000_000
        val point = JSONObject().apply {
            put("dataTypeName", dataTypeName)
            put("startTimeNanos", startNs)
            put("endTimeNanos", endNs)
            valueBuilder(this)
        }
        val body = JSONObject().apply {
            put("dataSourceId", dataStreamId)
            put("minStartTimeNs", startNs)
            put("maxEndTimeNs", endNs)
            put("point", JSONArray().put(point))
        }
        val request = Request.Builder()
            .url("$BASE_URL/dataSources/$dataStreamId/datasets/$startNs-$endNs")
            .header("Authorization", "Bearer $token")
            .patch(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 401) {
                clearToken(context)
                throw Exception("授權過期(401)")
            }
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${resp.body?.string()?.take(120)}")
            }
        }
    }

    /**
     * 測試雲端連線：確保 dataSource 可建立並寫入 1 筆 0 距離測試資料。
     * 回傳 null 表示成功，否則回傳錯誤訊息。
     * 若需要使用者授權，回傳 UserRecoverableAuthException 的 intent 供呼叫端啟動。
     */
    suspend fun testConnection(context: Context): Pair<String?, Intent?> {
        val account = getSignedInAccount(context)?.account
            ?: return "尚未登入 Google 帳號" to null
        return try {
            val id = ensureDataSource(
                context, account,
                "com.google.step_count.delta", "steps", "integer", STEP_STREAM_NAME
            )
            stepDataStreamId = id
            null to null
        } catch (e: UserRecoverableAuthException) {
            "需要授權" to e.intent
        } catch (e: Exception) {
            (e.message ?: "未知錯誤") to null
        }
    }
}
