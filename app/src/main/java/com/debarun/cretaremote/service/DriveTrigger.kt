package com.debarun.cretaremote.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone connecting to the car's Bluetooth is the earliest sign the car is on. It starts a drive session; when Android will
 * not let a background app start one, it falls back to a single live read so at least that moment is captured.
 */
class DriveTrigger : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = SettingsStore(ctx).load()
        if (st.carBtAddress.isBlank() || !st.driveSession) return
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (dev?.address != st.carBtAddress) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                DriveState.btOffAt = 0L
                if (!DriveService.start(ctx)) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            withTimeoutOrNull(9_000L) { (ctx.applicationContext as CretaApp).repo.refresh(live = true, quiet = true, light = true) }
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DriveState.btOffAt = System.currentTimeMillis()
        }
    }
}
