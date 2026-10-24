package com.debarun.cretaremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.Fill
import com.debarun.cretaremote.data.ServiceLog
import com.debarun.cretaremote.data.Settings
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as CretaApp).repo
    val data = repo.data

    init {
        viewModelScope.launch { repo.bootstrap() }
    }

    fun refresh(live: Boolean) { viewModelScope.launch { if (live) repo.syncLive() else repo.refresh(false) } }
    fun run(cmd: Cmd) { viewModelScope.launch { repo.run(cmd) } }
    fun cancelCmd() = repo.cancelCmd()
    fun checkUpdates() = repo.checkUpdates(manual = true)
    suspend fun placesFor(yyyymm: String) = repo.placesFor(yyyymm)
    suspend fun earliestPlaceMonth() = repo.earliestPlaceMonth()
    suspend fun dayRoute(day: String) = repo.dayRoute(day)
    fun clearRouteHistory() = repo.clearRouteHistory()
    fun installUpdate() = repo.installUpdate()
    fun saveTyreLog(psi: List<Double?>) = repo.saveTyreLog(psi)
    fun rawForSupport() = repo.rawForSupport()
    fun setFuelLimit(l: Int) = repo.setFuelLimit(l)
    fun saveFill(f: Fill, replaceTs: Long?): String? = repo.saveFill(f, replaceTs)
    fun deleteFill(ts: Long) = repo.deleteFill(ts)
    fun ackFuelAlarm() = repo.ackFuelAlarm()
    fun markActivitySeen() = repo.markActivitySeen()
    fun clearActivity() = repo.clearActivity()
    fun signIn(email: String, password: String, pin: String) { viewModelScope.launch { repo.signIn(email, password, pin) } }
    fun signOut() = repo.signOut()
    fun savePin(pin: String) = repo.savePin(pin)
    fun passwordChanged() = repo.passwordChanged()
    fun saveSettings(s: Settings) = repo.updateSettings(s)
    fun addService(log: ServiceLog) { viewModelScope.launch { repo.addService(log) } }
    fun deleteService(ts: Long) { viewModelScope.launch { repo.deleteService(ts) } }
    fun loadTrips(yyyymm: String) { viewModelScope.launch { repo.loadTrips(yyyymm) } }
}
