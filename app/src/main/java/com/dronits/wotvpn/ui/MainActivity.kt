package com.dronits.wotvpn.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dronits.wotvpn.R
import com.dronits.wotvpn.ui.theme.WotVpnTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory(applicationContext) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.notificationPermissionResult(granted && notificationsEnabled())
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.vpnPermissionResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Share links contain credentials; do not expose them in screenshots or task previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            WotVpnTheme {
                MainScreen(
                    state = state,
                    onConfigurationChanged = viewModel::updateConfiguration,
                    onConnectionClick = {
                        if (state.vpnActive) viewModel.disconnect() else viewModel.beginConnect()
                    },
                    onCancelRequest = viewModel::cancelRequest,
                    onNotificationSettings = ::openNotificationSettings,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState.map { it.step }.distinctUntilChanged().collect { step ->
                    handleConnectionStep(step)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshGameInstallation()
        viewModel.updateNotificationsEnabled(notificationsEnabled())
    }

    private fun handleConnectionStep(step: ConnectionStep) {
        when (step) {
            ConnectionStep.RequestNotifications -> {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.notificationPromptLaunched()
                    try {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } catch (_: RuntimeException) {
                        viewModel.permissionDialogFailed()
                    }
                } else {
                    viewModel.skipNotificationPrompt(notificationsEnabled())
                }
            }

            ConnectionStep.RequestVpn -> {
                try {
                    val consentIntent = VpnService.prepare(this)
                    if (consentIntent == null) {
                        viewModel.vpnAlreadyPrepared()
                    } else {
                        viewModel.vpnPromptLaunched()
                        vpnPermission.launch(consentIntent)
                    }
                } catch (_: RuntimeException) {
                    viewModel.permissionDialogFailed()
                }
            }

            ConnectionStep.ReadyToStart -> viewModel.startPreparedConnection()
            ConnectionStep.Idle,
            ConnectionStep.AwaitingNotifications,
            ConnectionStep.AwaitingVpn,
            -> Unit
        }
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null)),
                )
            } catch (_: RuntimeException) {
                showNotificationSettingsUnavailable()
            }
        } catch (_: RuntimeException) {
            showNotificationSettingsUnavailable()
        }
    }

    private fun showNotificationSettingsUnavailable() {
        Toast.makeText(this, R.string.error_settings_unavailable, Toast.LENGTH_SHORT).show()
    }
}
