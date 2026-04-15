package com.example.myapplication.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.service.NmeaService
import com.example.myapplication.service.NmeaUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var service: NmeaService? = null
    private var isBound = false

    private val _uiState = MutableStateFlow(NmeaUiState())
    val uiState: StateFlow<NmeaUiState> = _uiState.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as NmeaService.LocalBinder).getService()
            isBound = true
            // Подписываемся на StateFlow сервиса и ретранслируем в ViewModel
            viewModelScope.launch {
                service!!.uiState.collect { state ->
                    _uiState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBound = false
        }
    }

    /** Запустить сервис и привязаться к нему */
    fun bindService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, NmeaService::class.java)
        ctx.startService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /** Отвязаться от сервиса (сервис продолжит работать в фоне) */
    fun unbindService() {
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
    }

    fun connect(host: String, port: Int) {
        service?.startListening(host, port)
    }

    /** Остановить приём данных */
    fun disconnect() {
        service?.stopListening()
    }

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
