package com.debarun.cretaremote.quick

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debarun.cretaremote.ui.C
import com.debarun.cretaremote.ui.CretaTheme
import com.debarun.cretaremote.ui.GhostButton
import com.debarun.cretaremote.ui.PrimaryButton
import com.debarun.cretaremote.ui.Segmented
import com.debarun.cretaremote.ui.Txt
import kotlin.math.roundToInt

/**
 * Per-widget settings, shown when a widget is added and again from the launcher's widget menu (Settings):
 * theme and background, with a live preview. Backing out while adding cancels the widget, as Android expects.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val mgr = AppWidgetManager.getInstance(this)
        val meter = mgr.getAppWidgetInfo(id)?.provider?.className
            ?.let { runCatching { Class.forName(it).getDeclaredConstructor().newInstance() }.getOrNull() } as? MeterWidget
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 160
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 160
        val start = WidgetPrefs.get(this, id)
        setContent {
            CretaTheme {
                ConfigScreen(
                    meter = meter, wDp = w.toFloat(), hDp = h.toFloat(), start = start,
                    onDone = { cfg ->
                        WidgetPrefs.set(this, id, cfg)
                        Widgets.updateAll(this)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ConfigScreen(meter: MeterWidget?, wDp: Float, hDp: Float, start: WidgetPrefs.Cfg, onDone: (WidgetPrefs.Cfg) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var theme by remember { mutableIntStateOf(start.theme) }
    var clear by remember { mutableStateOf(start.clear) }
    var transparency by remember { mutableFloatStateOf(start.transparency.toFloat()) }
    val cfg = WidgetPrefs.Cfg(theme, clear, transparency.roundToInt())
    Column(
        Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Txt("Widget settings", size = 22.sp, weight = FontWeight.SemiBold)
        if (meter != null) {
            val bmp = remember(cfg.theme, cfg.clear, cfg.transparency) {
                val dark = when (cfg.theme) {
                    WidgetPrefs.DARK -> true
                    WidgetPrefs.AUTO -> Widgets.phoneIsDark(ctx)
                    else -> false
                }
                meter.bitmap(ctx, wDp, hDp, Widgets.Style(dark, cfg.alpha))
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2B1A10), Color(0xFFD07020))))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(bmp.asImageBitmap(), "Widget preview", Modifier.widthIn(max = wDp.dp).fillMaxWidth().aspectRatio(wDp / hDp))
            }
        }
        Txt("Theme", size = 14.sp, weight = FontWeight.Medium)
        Segmented(listOf("Light", "Dark", "Auto"), theme) { theme = it }
        Txt("Auto follows your phone's light or dark mode.", size = 11.sp, color = C.Faint)
        Txt("Background", size = 14.sp, weight = FontWeight.Medium)
        Segmented(listOf("Solid", "Transparent"), if (clear) 1 else 0) { clear = it == 1 }
        if (clear) {
            Txt("Transparency ${transparency.roundToInt()}%", size = 14.sp, weight = FontWeight.Medium)
            Slider(
                value = transparency, onValueChange = { transparency = it }, valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = C.Red, activeTrackColor = C.Red),
            )
        }
        PrimaryButton("Done", { onDone(cfg) }, Modifier.fillMaxWidth())
        GhostButton("Cancel", onCancel, Modifier.fillMaxWidth(), color = C.Dim)
    }
}
