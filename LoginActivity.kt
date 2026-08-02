package com.gpswalker.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gpswalker.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import android.view.View
import com.google.android.gms.common.api.ApiException

class LoginActivity : AppCompatActivity() {
    
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var tvCurrentAccount: TextView
    private lateinit var btnGoogleSignIn: Button
    private lateinit var btnSignOutLogin: Button
    private lateinit var tvSkip: TextView
    
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            updateUI(account)
            Toast.makeText(this, "歡迎，${account.displayName}！", Toast.LENGTH_SHORT).show()
        } catch (e: ApiException) {
            updateUI(null)
            Toast.makeText(this, "登入失敗（需要 OAuth 設定），將以本地模式繼續使用", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvCurrentAccount = findViewById(R.id.tvCurrentAccount)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        btnSignOutLogin = findViewById(R.id.btnSignOutLogin)
        tvSkip = findViewById(R.id.tvSkip)
        
        // Always show login screen first to allow account management
        // val account = GoogleSignIn.getLastSignedInAccount(this)
        // if (account != null) {
        //     navigateToMain()
        //     return
        // }
        
        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        // Sign in button
        btnGoogleSignIn.setOnClickListener {
            signInLauncher.launch(googleSignInClient.signInIntent)
        }

        // Sign out button on login screen
        btnSignOutLogin.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener(this) {
                Toast.makeText(this, "已登出 Google 帳號", Toast.LENGTH_SHORT).show()
                updateUI(null)
            }
        }
        
        // Skip button
        tvSkip.setOnClickListener {
            navigateToMain()
        }
    }
    
    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun updateUI(account: GoogleSignInAccount?) {
        if (account != null) {
            tvCurrentAccount.text = "已登入：${account.displayName ?: account.email}"
            tvCurrentAccount.visibility = View.VISIBLE
            btnGoogleSignIn.visibility = View.GONE
            btnSignOutLogin.visibility = View.VISIBLE
            tvSkip.text = "繼續使用 (已登入)"
        } else {
            tvCurrentAccount.visibility = View.GONE
            btnGoogleSignIn.visibility = View.VISIBLE
            btnSignOutLogin.visibility = View.GONE
            tvSkip.text = "跳過 (本地模式)"
        }
    }
}
