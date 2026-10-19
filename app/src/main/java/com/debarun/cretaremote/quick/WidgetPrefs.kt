package com.debarun.cretaremote.quick

import android.content.Context
import androidx.core.content.edit

/**
 * Look of each placed widget, set on the widget's own settings screen: theme, and a solid or transparent background
 * with an adjustable amount of transparency. Defaults to the light theme on a solid background.
 */
object WidgetPrefs {
    const val LIGHT = 0
    const val DARK = 1
    const val AUTO = 2

    class Cfg(val theme: Int = LIGHT, val clear: Boolean = false, val transparency: Int = 60) {
        /** Background opacity, 0 to 255. */
        val alpha: Int get() = if (clear) 255 * (100 - transparency.coerceIn(0, 100)) / 100 else 255
    }

    private fun prefs(c: Context) = c.getSharedPreferences("widget_cfg", Context.MODE_PRIVATE)

    fun get(c: Context, id: Int): Cfg {
        val p = prefs(c)
        return Cfg(p.getInt("theme_$id", LIGHT), p.getBoolean("clear_$id", false), p.getInt("tr_$id", 60))
    }

    fun set(c: Context, id: Int, cfg: Cfg) {
        prefs(c).edit {
            putInt("theme_$id", cfg.theme)
            putBoolean("clear_$id", cfg.clear)
            putInt("tr_$id", cfg.transparency)
        }
    }

    fun remove(c: Context, id: Int) {
        prefs(c).edit { remove("theme_$id").remove("clear_$id").remove("tr_$id") }
    }
}
