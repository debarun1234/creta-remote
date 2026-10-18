package com.debarun.cretaremote.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.debarun.cretaremote.CretaApp

/** The "I'll fill up" button on the fuel alarm: stops the ringing. */
class FuelAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACK) return
        (context.applicationContext as CretaApp).repo.ackFuelAlarm()
    }

    companion object {
        const val ACK = "com.debarun.cretaremote.FUEL_ACK"
    }
}
