package com.debarun.cretaremote

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.debarun.cretaremote.ui.CretaTheme
import com.debarun.cretaremote.ui.Root

class MainActivity : FragmentActivity() {
    private val launchAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        launchAction.value = intent?.action
        setContent {
            CretaTheme {
                Root(launchAction = launchAction, confirm = this::confirm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchAction.value = intent.action
    }

    /** Gate sensitive commands behind fingerprint / face / device PIN. Passes through if the phone has no secure lock. */
    private fun confirm(title: String, onOk: () -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            onOk()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(auth)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            onOk()
        }
    }
}
