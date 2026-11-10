package com.dronits.wotvpn.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dronits.wotvpn.config.ProxyConfigInput
import com.dronits.wotvpn.vpn.AndroidVpnClient
import com.dronits.wotvpn.vpn.VpnClient
import com.dronits.wotvpn.vpn.VpnState
import com.dronits.wotvpn.vpn.WotGame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class MainViewModel(
    private val vpnClient: VpnClient,
    private val isGameInstalled: () -> Boolean,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        MainUiState(gameInstalled = isGameInstalled(), vpnState = vpnClient.state.value),
    )
    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()

    // Deliberately not a SavedStateHandle: proxy credentials must not enter saved instance state.
    private var pendingConfiguration: String? = null

    init {
        viewModelScope.launch {
            vpnClient.state.collect { state ->
                mutableUiState.update { it.copy(vpnState = state) }
            }
        }
    }

    fun updateConfiguration(value: String) {
        if (!uiState.value.configurationEditable || vpnClient.state.value.isActive()) return
        mutableUiState.update {
            it.copy(configuration = value, configurationError = null, actionError = null)
        }
    }

    fun refreshGameInstallation() {
        mutableUiState.update { it.copy(gameInstalled = isGameInstalled()) }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        mutableUiState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun beginConnect() {
        if (!uiState.value.configurationEditable || vpnClient.state.value.isActive()) return
        refreshGameInstallation()
        if (!uiState.value.gameInstalled) return

        val error = ProxyConfigInput.validate(uiState.value.configuration)
        if (error != null) {
            mutableUiState.update { it.copy(configurationError = error, actionError = null) }
            return
        }
        pendingConfiguration = ProxyConfigInput.normalize(uiState.value.configuration)
        mutableUiState.update {
            it.copy(
                configurationError = null,
                actionError = null,
                step = ConnectionStep.RequestNotifications,
            )
        }
    }

    fun notificationPromptLaunched() {
        advance(ConnectionStep.RequestNotifications, ConnectionStep.AwaitingNotifications)
    }

    fun skipNotificationPrompt(enabled: Boolean) {
        if (uiState.value.step != ConnectionStep.RequestNotifications) return
        updateNotificationsEnabled(enabled)
        advance(ConnectionStep.RequestNotifications, ConnectionStep.RequestVpn)
    }

    fun notificationPermissionResult(enabled: Boolean) {
        if (uiState.value.step != ConnectionStep.AwaitingNotifications) return
        updateNotificationsEnabled(enabled)
        // Denial is not fatal: Android allows a VPN foreground service without drawer notifications.
        advance(ConnectionStep.AwaitingNotifications, ConnectionStep.RequestVpn)
    }

    fun vpnPromptLaunched() {
        advance(ConnectionStep.RequestVpn, ConnectionStep.AwaitingVpn)
    }

    fun vpnAlreadyPrepared() {
        advance(ConnectionStep.RequestVpn, ConnectionStep.ReadyToStart)
    }

    fun vpnPermissionResult(granted: Boolean) {
        if (uiState.value.step != ConnectionStep.AwaitingVpn) return
        if (granted) {
            advance(ConnectionStep.AwaitingVpn, ConnectionStep.ReadyToStart)
        } else {
            failRequest(UiActionError.VpnConsentDenied)
        }
    }

    fun permissionDialogFailed() {
        if (uiState.value.permissionRequestInProgress) {
            failRequest(UiActionError.PermissionDialogFailed)
        }
    }

    /** Called by the activity only while RESUMED, never from a background permission callback. */
    fun startPreparedConnection() {
        if (uiState.value.step != ConnectionStep.ReadyToStart) return
        val configuration = pendingConfiguration ?: return cancelRequest()
        refreshGameInstallation()
        if (!uiState.value.gameInstalled) {
            cancelRequest()
            return
        }
        pendingConfiguration = null
        try {
            vpnClient.connect(configuration)
            mutableUiState.update {
                it.copy(step = ConnectionStep.Idle)
            }
        } catch (_: RuntimeException) {
            failRequest(UiActionError.ServiceStartFailed)
        }
    }

    fun disconnect() {
        cancelRequest()
        if (!vpnClient.state.value.isActive()) return
        try {
            vpnClient.disconnect()
            mutableUiState.update { it.copy(actionError = null) }
        } catch (_: RuntimeException) {
            mutableUiState.update { it.copy(actionError = UiActionError.ServiceStopFailed) }
        }
    }

    fun cancelRequest() {
        pendingConfiguration = null
        mutableUiState.update { it.copy(step = ConnectionStep.Idle) }
    }

    private fun advance(expected: ConnectionStep, next: ConnectionStep) {
        if (pendingConfiguration == null || uiState.value.step != expected) return
        mutableUiState.update { it.copy(step = next) }
    }

    private fun failRequest(error: UiActionError) {
        pendingConfiguration = null
        mutableUiState.update {
            it.copy(step = ConnectionStep.Idle, actionError = error)
        }
    }

    override fun onCleared() {
        pendingConfiguration = null
        mutableUiState.value = MainUiState()
        super.onCleared()
    }

    private fun VpnState.isActive(): Boolean = this == VpnState.Preparing || this == VpnState.Connected

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            return MainViewModel(AndroidVpnClient(applicationContext)) {
                WotGame.isInstalled(applicationContext)
            } as T
        }
    }
}
