package com.gpswalker.app.ui.screens

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Health Connect 權限說明頁。
 * 當用戶在 Health Connect 授權對話框點擊「隱私權政策」時顯示。
 */
class HealthRationaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(0xFFF7FBFA.toInt())
        }

        val title = TextView(this).apply {
            text = "GPS Walker 健康資料使用說明"
            textSize = 22f
            setTextColor(0xFF0ABAB5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad)
        }

        val body = TextView(this).apply {
            text = "GPS Walker 需要以下 Health Connect 權限：\n\n" +
                "• 寫入步數：將模擬行走產生的步數同步到 Health Connect\n" +
                "• 寫入距離：將模擬行走的距離同步到 Health Connect\n" +
                "• 讀取步數 / 距離：用於確認同步結果\n\n" +
                "所有資料僅寫入您裝置上的 Health Connect，" +
                "GPS Walker 不會將任何健康資料上傳到伺服器或與第三方分享。\n\n" +
                "本 App 僅供個人測試用途。"
            textSize = 15f
            setTextColor(0xFF444444.toInt())
            setLineSpacing(0f, 1.3f)
        }

        val btn = Button(this).apply {
            text = "我知道了"
            setOnClickListener { finish() }
        }

        layout.addView(title)
        layout.addView(body)
        layout.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        setContentView(layout)
    }
}
