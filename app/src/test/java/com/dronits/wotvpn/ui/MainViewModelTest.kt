package com.dronits.wotvpn.ui

import androidx.lifecycle.ViewModelStore
import com.dronits.wotvpn.config.ConfigInputError
import com.dronits.wotvpn.vpn.VpnClient
import com.dronits.wotvpn.vpn.VpnState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var store: ViewModelStore
    private lateinit var client: FakeVpnClient
    private lateinit var model: MainViewModel
    private var gameInstalled = true

    @Before
    fun setUp() {
        store = ViewModelStore()
        client = FakeVpnClient()
        gameInstalled = true
        model = MainViewModel(client) { gameInstalled }
        store.put("main", model)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun missingGamePreventsPermissionRequestsAndServiceStart() = runTest {
        gameInstalled = false
        model.updateConfiguration(VALID_LINK)
        model.beginConnect()

        assertFalse(model.uiState.value.gameInstalled)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun invalidConfigurationIsShownBeforeAnyPermissionRequest() = runTest {
        model.updateConfiguration("https://example.invalid/subscription")
        model.beginConnect()

        assertEquals(ConfigInputError.UnsupportedScheme, model.uiState.value.configurationError)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun notificationDenialDoesNotBlockAConsentedVpnRequest() = runTest {
        model.updateConfiguration(" \n$VALID_LINK\n ")
        model.beginConnect()
        model.notificationPromptLaunched()
        model.notificationPermissionResult(enabled = false)

        assertFalse(model.uiState.value.notificationsEnabled)
        assertEquals(ConnectionStep.RequestVpn, model.uiState.value.step)
        model.vpnPromptLaunched()
        model.vpnPermissionResult(granted = true)
        model.startPreparedConnection()
        runCurrent()

        assertEquals(listOf(VALID_LINK), client.configurations)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertEquals(VpnState.Preparing, model.uiState.value.vpnState)
        assertNull(model.uiState.value.actionError)
    }

    @Test
    fun vpnDenialAbortsWithoutStartingTheService() = runTest {
        prepareUntilVpnRequest()
        model.vpnPromptLaunched()
        model.vpnPermissionResult(granted = false)
        model.startPreparedConnection()

        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertEquals(UiActionError.VpnConsentDenied, model.uiState.value.actionError)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun cancelledRequestIgnoresLatePermissionResults() = runTest {
        prepareUntilVpnRequest()
        model.vpnPromptLaunched()
        model.cancelRequest()
        model.vpnPermissionResult(granted = true)
        model.startPreparedConnection()

        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun permissionResultWithoutAnInMemoryRequestDoesNotAutoConnect() = runTest {
        model.notificationPermissionResult(enabled = true)
        model.vpnPermissionResult(granted = true)
        model.startPreparedConnection()

        assertEquals("", model.uiState.value.configuration)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun duplicateClicksCannotChangeThePendingConfigurationOrStartTwice() = runTest {
        model.updateConfiguration(VALID_LINK)
        model.beginConnect()
        model.beginConnect()
        model.updateConfiguration("trojan://another@example.invalid:443")
        model.skipNotificationPrompt(enabled = true)
        model.vpnAlreadyPrepared()
        model.startPreparedConnection()
        model.startPreparedConnection()
        runCurrent()

        assertEquals(VALID_LINK, model.uiState.value.configuration)
        assertEquals(listOf(VALID_LINK), client.configurations)
        assertFalse(model.uiState.value.configurationEditable)
    }

    @Test
    fun removalOfTheGameDuringConsentIsRecheckedBeforeStart() = runTest {
        prepareUntilVpnRequest()
        model.vpnPromptLaunched()
        model.vpnPermissionResult(granted = true)
        gameInstalled = false
        model.startPreparedConnection()

        assertFalse(model.uiState.value.gameInstalled)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun coreUnavailableIsAnErrorAndNeverAnArtificialConnectedState() = runTest {
        prepareUntilVpnRequest()
        model.vpnAlreadyPrepared()
        model.startPreparedConnection()
        client.mutableState.value = VpnState.Error("Прокси-ядро не интегрировано")
        runCurrent()

        assertTrue(model.uiState.value.vpnState is VpnState.Error)
        assertFalse(model.uiState.value.vpnActive)
        assertTrue(model.uiState.value.configurationEditable)
    }

    @Test
    fun repeatedIdenticalServiceErrorsDoNotLeaveUiStuckInPreparing() = runTest {
        val unavailable = VpnState.Error("Прокси-ядро не интегрировано")
        client.mutableState.value = unavailable
        runCurrent()
        prepareUntilVpnRequest()
        model.vpnAlreadyPrepared()
        model.startPreparedConnection()
        // StateFlow may conflate a very fast Preparing -> identical Error transition.
        client.mutableState.value = unavailable
        runCurrent()

        assertEquals(unavailable, model.uiState.value.vpnState)
        assertFalse(model.uiState.value.vpnActive)
        assertTrue(model.uiState.value.configurationEditable)
    }

    @Test
    fun foregroundStartFailureIsVisibleAndAllowsAnotherAttempt() = runTest {
        client.failStart = true
        prepareUntilVpnRequest()
        model.vpnAlreadyPrepared()
        model.startPreparedConnection()

        assertEquals(UiActionError.ServiceStartFailed, model.uiState.value.actionError)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
        model.beginConnect()
        assertEquals(ConnectionStep.RequestNotifications, model.uiState.value.step)
        assertNull(model.uiState.value.actionError)
    }

    @Test
    fun failedPermissionDialogClearsThePendingRequest() = runTest {
        model.updateConfiguration(VALID_LINK)
        model.beginConnect()
        model.notificationPromptLaunched()
        model.permissionDialogFailed()
        model.notificationPermissionResult(enabled = true)
        model.startPreparedConnection()

        assertEquals(UiActionError.PermissionDialogFailed, model.uiState.value.actionError)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
        assertTrue(client.configurations.isEmpty())
    }

    @Test
    fun disconnectRemainsAvailableIfTheGameWasUninstalled() = runTest {
        client.mutableState.value = VpnState.Connected
        runCurrent()
        gameInstalled = false
        model.refreshGameInstallation()
        model.disconnect()
        runCurrent()

        assertEquals(1, client.disconnectCount)
        assertEquals(VpnState.Disconnected, model.uiState.value.vpnState)
        assertFalse(model.uiState.value.gameInstalled)
    }

    @Test
    fun clearingTheViewModelDropsTheInMemoryDraft() = runTest {
        model.updateConfiguration(VALID_LINK)
        model.beginConnect()
        store.clear()

        assertEquals("", model.uiState.value.configuration)
        assertEquals(ConnectionStep.Idle, model.uiState.value.step)
    }

    private fun prepareUntilVpnRequest() {
        model.updateConfiguration(VALID_LINK)
        model.beginConnect()
        model.skipNotificationPrompt(enabled = true)
        assertEquals(ConnectionStep.RequestVpn, model.uiState.value.step)
    }

    private class FakeVpnClient : VpnClient {
        val mutableState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        override val state = mutableState.asStateFlow()
        val configurations = mutableListOf<String>()
        var disconnectCount = 0
        var failStart = false

        override fun connect(configuration: String) {
            if (failStart) throw SecurityException("Foreground start denied")
            configurations += configuration
            mutableState.value = VpnState.Preparing
        }

        override fun disconnect() {
            disconnectCount += 1
            mutableState.value = VpnState.Disconnected
        }
    }

    private companion object {
        const val VALID_LINK = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=tls"
    }
}
