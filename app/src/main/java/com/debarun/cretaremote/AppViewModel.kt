package com.debarun.cretaremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.debarun.cretaremote.data.Cmd
import com.debarun.cretaremote.data.ServiceLog
import com.debarun.cretaremote.data.Settings
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as CretaApp).repo
    val data = repo.data

    init {
        viewModelScope.launch { repo.bootstrap() }
    }

    fun refresh(live: Boolean) { viewModelScope.launch { repo.refresh(live) } }
    fun run(cmd: Cmd) { viewModelScope.launch { repo.run(cmd) } }
    fun cancelCmd() = repo.cancelCmd()
    fun signIn(email: String, password: String, pin: String) { viewModelScope.launch { repo.signIn(email, password, pin) } }
    fun signOut() = repo.signOut()
    fun savePin(pin: String) = repo.savePin(pin)
    fun saveSettings(s: Settings) = repo.updateSettings(s)
    fun addService(log: ServiceLog) { viewModelScope.launch { repo.addService(log) } }
    fun deleteService(ts: Long) { viewModelScope.launch { repo.deleteService(ts) } }
    fun loadTrips(yyyymm: String) { viewModelScope.launch { repo.loadTrips(yyyymm) } }
}
