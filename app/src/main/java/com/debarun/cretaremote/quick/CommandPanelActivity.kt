package com.debarun.cretaremote.quick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.data.AppForeground
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.Phase
import com.debarun.cretaremote.ui.CommandBanner
import com.debarun.cretaremote.ui.CretaTheme
import kotlinx.coroutines.delay

/**
 * What a widget key opens: a see-through screen over the home screen that sends the command and shows the same progress panel as inside
 * the app (sending, waiting for the car, result), then goes away by itself. Tap outside the panel or press back to close it early; the
 * command carries on and its result still arrives as a notification.
 */
class CommandPanelActivity : ComponentActivity() {
    private val repo get() = (application as CretaApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !start(intent)) { finish(); return }
        setContent {
            CretaTheme {
                val data by repo.data.collectAsStateWithLifecycle()
                var seen by remember { mutableStateOf(false) }
                val phase = data.cmd.phase
                LaunchedEffect(phase) {
                    if (phase != Phase.IDLE) seen = true else if (seen) finish()
                }
                // the command never started (for example another one was already running and finished): do not leave an empty screen up
                LaunchedEffect(Unit) { delay(4_000); if (!seen) finish() }
                Box(
                    Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CommandBanner(data, onCancel = { repo.cancelCmd() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.visible = true // the panel is the result, so no separate notification while it is up
    }

    override fun onStop() {
        AppForeground.visible = false
        super.onStop()
    }

    private fun start(i: Intent?): Boolean {
        val cmd = runCatching { Cmd.valueOf(i?.getStringExtra("cmd").orEmpty()) }.getOrNull() ?: return false
        repo.runDetached(cmd)
        return true
    }
}
