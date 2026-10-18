package com.debarun.cretaremote.quick

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.CmdUi
import com.debarun.cretaremote.data.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One-swipe car control from the notification shade, no app launch. Unlock makes Android ask for the phone's
 * lock screen first, so a stolen locked phone can't open the car.
 */
abstract class CarTile : TileService() {
    protected abstract val cmd: Cmd
    protected abstract val idleLabel: String
    protected abstract val needsDeviceUnlock: Boolean

    private var job: Job? = null
    private val repo get() = (application as CretaApp).repo

    override fun onStartListening() {
        super.onStartListening()
        render(repo.data.value.cmd)
        job = CoroutineScope(Dispatchers.Main).launch {
            repo.data.map { it.cmd }.distinctUntilChanged().collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val c = repo.data.value.cmd
        if (c.busy && c.cmd == cmd) { repo.cancelCmd(); return } // tapping the tile again cancels
        val go = { repo.runDetached(cmd) }
        if (needsDeviceUnlock && isLocked) unlockAndRun(go) else go()
    }

    private fun render(c: CmdUi) {
        val tile = qsTile ?: return
        val mine = c.cmd == cmd
        val text = when {
            !mine -> idleLabel
            c.phase == Phase.SENDING -> "Sending..."
            c.phase == Phase.WAITING -> "Waiting..."
            c.phase == Phase.OK -> "Done"
            c.phase == Phase.FAIL -> "Failed"
            c.phase == Phase.CANCELLED -> "Cancelled"
            else -> idleLabel
        }
        tile.label = idleLabel
        tile.state = if (mine && c.busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (text == idleLabel) null else text
        tile.updateTile()
    }
}

class LockTile : CarTile() {
    override val cmd = Cmd.LOCK
    override val idleLabel = "Lock Creta"
    override val needsDeviceUnlock = false
}

class LightsTile : CarTile() {
    override val cmd = Cmd.LIGHTS
    override val idleLabel = "Creta lights"
    override val needsDeviceUnlock = false
}

class BootTile : CarTile() {
    override val cmd = Cmd.BOOT
    override val idleLabel = "Creta boot"
    override val needsDeviceUnlock = true
}

class StartTile : CarTile() {
    override val cmd = Cmd.START
    override val idleLabel = "Start Creta"
    override val needsDeviceUnlock = true
}

class UnlockTile : CarTile() {
    override val cmd = Cmd.UNLOCK
    override val idleLabel = "Unlock Creta"
    override val needsDeviceUnlock = true
}
