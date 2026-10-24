package com.debarun.cretaremote.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
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
    const val CH_DRIVE = "drive"
    private const val CH_UPDATES = "updates"
    const val ID_UPDATE = 304
    private const val CH_ALERTS = "alerts"
    private const val CH_FUEL_ALARM = "fuel_alarm"
    const val ID_FUEL_HEADS_UP = 301
    const val ID_FUEL_ALARM = 302
    const val ID_PASSWORD = 303

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_COMMANDS, "Command results", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "How lock, unlock, engine and horn commands ended, when the app is closed" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A newer version of Creta Remote is available on GitHub" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DRIVE, "Connected to car", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the app is reading the car live because the engine is on"; setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Car alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Low fuel, something left open, car left unlocked" },
        )
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(CH_FUEL_ALARM, "Fuel alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings until you acknowledge that the tank has reached your fuel limit"
                setSound(alarmSound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 700)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun enabled(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String, action: String? = null) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).also { i -> if (action != null) i.action = action },
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
        Cmd.LIGHTS -> "Lights"
        Cmd.BOOT -> "Boot release"
    }

    /** A newer release exists on GitHub. Tapping it opens Settings and starts the update. */
    fun update(ctx: Context, version: String, notes: String) {
        val line = notes.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() }?.take(120)
        post(ctx, ID_UPDATE, CH_UPDATES, "Creta Remote $version is available", line ?: "Tap to update. Your data stays.", com.debarun.cretaremote.data.Updater.ACTION)
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

    /** The short alert, a couple of litres before the limit. */
    fun fuelHeadsUp(ctx: Context, litres: Double, limit: Int) =
        post(ctx, ID_FUEL_HEADS_UP, CH_ALERTS, "Fuel nearing your $limit L limit", "About ${"%.1f".format(litres)} L left. Plan a fill-up.")

    /** The alarm: loud, ongoing and repeating until the driver taps "I'll fill up". */
    @SuppressLint("MissingPermission", "FullScreenIntentPolicy")
    fun fuelAlarm(ctx: Context, litres: Double, limit: Int) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ack = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, com.debarun.cretaremote.quick.FuelAckReceiver::class.java).setAction(com.debarun.cretaremote.quick.FuelAckReceiver.ACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Only about ${"%.1f".format(litres)} L left, at your $limit L limit. Fill the car."
        val n = NotificationCompat.Builder(ctx, CH_FUEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(0xFFE3192D.toInt())
            .setContentTitle("Fill the car now")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)
            .apply { if (canUseFullScreen(ctx)) setFullScreenIntent(open, true) }
            .addAction(0, "I'll fill up", ack)
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT // keep ringing until dismissed
        try {
            nm.notify(ID_FUEL_ALARM, n)
        } catch (e: SecurityException) {
        }
    }

    /** From Android 14 the user can withdraw full-screen notifications from an app, so ask first. */
    private fun canUseFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    fun cancelFuelAlarm(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_FUEL_ALARM)
}
