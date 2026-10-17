package com.debarun.cretaremote.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.debarun.cretaremote.MainActivity
import com.debarun.cretaremote.R

/** True while the app's screen is visible; notifications are only for when it isn't. */
object AppForeground {
    @Volatile var visible = false
}

/** System notifications: command results and car alerts. Everything checks that the user has allowed notifications. */
object Notifier {
    private const val CH_COMMANDS = "commands"
    private const val CH_ALERTS = "alerts"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_COMMANDS, "Command results", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "How lock, unlock, engine and horn commands ended, when the app is closed" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Car alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Low fuel, something left open, car left unlocked" },
        )
    }

    fun enabled(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(0xFFE3192D.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // permission revoked between the check and the call
        }
    }

    private fun name(c: Cmd) = when (c) {
        Cmd.LOCK -> "Lock"
        Cmd.UNLOCK -> "Unlock"
        Cmd.START -> "Engine start"
        Cmd.STOP -> "Engine stop"
        Cmd.HORN -> "Horn & lights"
    }

    fun command(ctx: Context, cmd: Cmd, outcome: Outcome, message: String) {
        val word = when (outcome) {
            Outcome.SUCCESS -> "done"
            Outcome.FAILED -> "failed"
            Outcome.CANCELLED -> "cancelled"
            Outcome.UNCONFIRMED -> "not confirmed"
        }
        post(ctx, 100 + cmd.ordinal, CH_COMMANDS, "${name(cmd)} $word", message)
    }

    fun alert(ctx: Context, id: Int, title: String, text: String) = post(ctx, id, CH_ALERTS, title, text)
}
