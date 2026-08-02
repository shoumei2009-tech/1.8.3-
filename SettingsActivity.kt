package com.gpswalker.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import com.gpswalker.app.R
import com.gpswalker.app.util.HealthConnectManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var googleSignInClient: GoogleSignInClient
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Health Connect permission launcher (formal ActivityResult contract)
    private val hcPermissionLauncher = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val tvHcStatus = findViewById<TextView>(R.id.tvHcStatus)
        if (granted.containsAll(healthConnectManager.permissions)) {
            tvHcStatus?.text = "✓ 已授權，可同步步數"
            tvHcStatus?.setTextColor(getColor(R.color.success_green))
            Toast.makeText(this, "Health Connect 已授權！", Toast.LENGTH_SHORT).show()
            // Auto-run a test write to confirm
            scope.launch {
                val ok = healthConnectManager.testConnection()
                if (ok) {
                    tvHcStatus?.text = "✓ 已連線且正常運作"
                    Toast.makeText(this@SettingsActivity, "測試寫入成功，步數同步正常", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            tvHcStatus?.text = "未授權"
            tvHcStatus?.setTextColor(getColor(R.color.warning_amber))
            Toast.makeText(this, "未完成授權，步數將無法同步", Toast.LENGTH_LONG).show()
        }
    }
    
    // Google Sign-In launcher（含切換帳號與登入處理）
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            Toast.makeText(this, "已登入：${account.email}", Toast.LENGTH_SHORT).show()
            setupAccountSection()
            // 登入後自動測試雲端連線
            testCloudConnection()
        } catch (e: ApiException) {
            Toast.makeText(this, "登入/切換失敗（錯誤代碼 ${e.statusCode}）", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        healthConnectManager = HealthConnectManager(this)
        healthConnectManager.initialize()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        setupAccountSection()
        setupHealthConnectSection()
        setupMovementSection()
        
        findViewById<ImageButton>(R.id.btnBack)?.setOnClickListener { finish() }
    }
    
    private fun setupAccountSection() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val tvEmail = findViewById<TextView>(R.id.tvEmail)
        val tvSyncStatus = findViewById<TextView>(R.id.tvSyncStatus)
        val btnSignOut = findViewById<Button>(R.id.btnSignOut)
        // 綁定「切換帳號」按鈕 (解決問題 1)
        val btnSwitchAccount = findViewById<View>(R.id.btnSwitchAccount) ?: findViewById<View>(R.id.tvSwitchAccount)
        
        if (account != null) {
            tvEmail?.text = "已登入：${account.displayName ?: account.email}"
            tvSyncStatus?.text = "透過 Health Connect 同步步數"
            tvSyncStatus?.setTextColor(getColor(R.color.text_secondary))
            
            // 【登出功能】
            btnSignOut?.text = "登出"
            btnSignOut?.setOnClickListener {
                googleSignInClient.signOut().addOnCompleteListener(this) {
                    googleSignInClient.revokeAccess().addOnCompleteListener(this) {
                        Toast.makeText(this, "已登出 Google 帳號", Toast.LENGTH_SHORT).show()
                        setupAccountSection() // 重新整理 UI
                    }
                }
            }
            
            // 【切換帳號功能】：先登出再立刻跳出 Google 帳號選擇器 (解決問題 1)
            btnSwitchAccount?.visibility = View.VISIBLE
            btnSwitchAccount?.setOnClickListener {
                googleSignInClient.signOut().addOnCompleteListener(this) {
                    val intent = googleSignInClient.signInIntent
                    signInLauncher.launch(intent)
                }
            }
        } else {
            tvEmail?.text = "尚未登入"
            tvSyncStatus?.text = "透過 Health Connect 同步步數"
            tvSyncStatus?.setTextColor(getColor(R.color.text_secondary))
            
            btnSignOut?.text = "登入"
            btnSignOut?.setOnClickListener {
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
            }
            
            btnSwitchAccount?.visibility = View.GONE
        }
    }
    
    private fun setupHealthConnectSection() {
        val tvHcStatus = findViewById<TextView>(R.id.tvHcStatus)
        val btnTestConnection = findViewById<Button>(R.id.btnTestConnection)
        val switchSync = findViewById<SwitchCompat>(R.id.switchHealthSync)
        
        val status = healthConnectManager.initialize()
        when (status) {
            HealthConnectManager.ConnectionStatus.CONNECTED -> {
                tvHcStatus?.text = "已連線"
                tvHcStatus?.setTextColor(getColor(R.color.success_green))
            }
            HealthConnectManager.ConnectionStatus.NOT_AUTHORIZED -> {
                tvHcStatus?.text = "未授權"
                tvHcStatus?.setTextColor(getColor(R.color.warning_amber))
            }
            HealthConnectManager.ConnectionStatus.UNAVAILABLE -> {
                tvHcStatus?.text = "無法使用 - 請安裝 Health Connect"
                tvHcStatus?.setTextColor(getColor(R.color.error_red))
            }
            HealthConnectManager.ConnectionStatus.ERROR -> {
                tvHcStatus?.text = "錯誤"
                tvHcStatus?.setTextColor(getColor(R.color.error_red))
            }
        }
        
        btnTestConnection?.setOnClickListener {
            scope.launch {
                btnTestConnection.isEnabled = false
                btnTestConnection.text = "測試中..."
                
                val hasPermissions = healthConnectManager.checkPermissions()
                if (!hasPermissions) {
                    requestHealthConnectPermissions()
                    btnTestConnection.isEnabled = true
                    btnTestConnection.text = getString(R.string.test_connection)
                    return@launch
                }
                
                val success = healthConnectManager.testConnection()
                if (success) {
                    tvHcStatus?.text = "✓ 已連線且正常運作"
                    tvHcStatus?.setTextColor(getColor(R.color.success_green))
                    Toast.makeText(this@SettingsActivity, "連線成功！步數寫入正常", Toast.LENGTH_SHORT).show()
                } else {
                    tvHcStatus?.text = "✗ 連線失敗"
                    tvHcStatus?.setTextColor(getColor(R.color.error_red))
                    Toast.makeText(this@SettingsActivity, "連線失敗，請確認 Health Connect 已安裝並授權", Toast.LENGTH_LONG).show()
                }
                btnTestConnection.isEnabled = true
                btnTestConnection.text = getString(R.string.test_connection)
            }
        }
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        switchSync?.isChecked = prefs.getBoolean("health_sync_enabled", true)
        switchSync?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("health_sync_enabled", isChecked).apply()
        }
    }
    
    private fun requestHealthConnectPermissions() {
        try {
            hcPermissionLauncher.launch(healthConnectManager.permissions)
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val intent = Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra("android.intent.extra.PACKAGE_NAME", packageName)
            }
            startActivity(intent)
            Toast.makeText(this, "請在 Health Connect 中授權 GPS Walker 的步數與距離權限", Toast.LENGTH_LONG).show()
            return
        } catch (_: Exception) {}
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.healthdata")
            if (intent != null) {
                startActivity(intent)
                Toast.makeText(this, "請在 Health Connect 中授權 GPS Walker", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "請先安裝 Health Connect App", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "無法開啟 Health Connect", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupMovementSection() {
        val tvStepLength = findViewById<TextView>(R.id.tvStepLength)
        val seekBar = findViewById<SeekBar>(R.id.seekStepLength)
        val tvSpeed = findViewById<TextView>(R.id.tvMovementSpeed)
        
        tvSpeed?.text = "20 km/h"
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedLength = prefs.getFloat("step_length", 0.75f)
        seekBar?.progress = ((savedLength - 0.5f) * 20).toInt()
        tvStepLength?.text = String.format("%.2f m", savedLength)
        
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val length = 0.5f + progress * 0.05f
                tvStepLength?.text = String.format("%.2f m", length)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val length = 0.5f + (seekBar?.progress ?: 5) * 0.05f
                prefs.edit().putFloat("step_length", length).apply()
            }
        })
    }
    
    // 預防錯誤：補上自動測試連線功能的空殼
    private fun testCloudConnection() {
        // 這邊如果原本有寫入雲端的程式碼，可以在未來加回來
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
