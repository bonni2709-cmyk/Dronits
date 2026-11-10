package com.dronits.wotvpn.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dronits.wotvpn.R
import com.dronits.wotvpn.config.ProxyConfigInput
import com.dronits.wotvpn.config.messageRes
import com.dronits.wotvpn.core.CoreFailure
import com.dronits.wotvpn.core.ProxyManager
import com.dronits.wotvpn.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WotVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionJob: Job? = null
    private var stopJob: Job? = null
    private var stopRequested = false
    private var destroyed = false
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.vpn_notification_channel_description) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent?.getStringExtra(EXTRA_PROXY_CONFIG))
            else -> stopConnection()
        }
        return START_NOT_STICKY
    }

    private fun connect(configuration: String?) {
        if (connectionJob != null || stopJob != null) return
        stopRequested = false
        mutableState.value = VpnState.Preparing
        try {
            foreground(getString(R.string.vpn_preparing))
            if (!WotGame.isInstalled(this)) {
                finishWithError(getString(R.string.error_game_not_installed))
                return
            }
            val inputError = ProxyConfigInput.validate(configuration.orEmpty())
            if (inputError != null) {
                finishWithError(getString(inputError.messageRes))
                return
            }
            if (VpnService.prepare(this) != null) {
                finishWithError(getString(R.string.error_vpn_permission))
                return
            }
        } catch (_: RuntimeException) {
            finishWithError(getString(R.string.error_vpn_start))
            return
        }
        val manager = ProxyManager(this)
        connectionJob = scope.launch {
            var failure: String? = null
            try {
                manager.connect(configuration.orEmpty()) {
                    if (VpnService.prepare(this@WotVpnService) != null) throw CoreFailure(R.string.error_vpn_permission)
                    // A fresh builder, one allowed package, and no catch-all fallback, even during uninstall races.
                    GameTunnelPolicy.configure(Builder(), getString(R.string.app_name)).establish()
                        ?: throw CoreFailure(R.string.error_tun_establish)
                }
                ensureActive()
                mutableState.value = VpnState.Connected
                foreground(getString(R.string.vpn_connected))
                while (isActive) {
                    delay(1_000)
                    if (!withContext(Dispatchers.IO) { manager.isRunning() }) {
                        throw CoreFailure(R.string.error_core_stopped)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: CoreFailure) {
                failure = getString(exception.messageRes)
            } catch (_: PackageManager.NameNotFoundException) {
                failure = getString(R.string.error_game_not_installed)
            } catch (_: SecurityException) {
                failure = getString(R.string.error_vpn_permission)
            } catch (_: LinkageError) {
                failure = getString(R.string.error_native_unavailable)
            } catch (_: Exception) {
                failure = getString(R.string.error_vpn_start)
            } finally {
                withContext(NonCancellable) {
                    if (failure != null && !stopRequested) mutableState.value = VpnState.Stopping
                    withContext(Dispatchers.IO) { manager.close() }
                    connectionJob = null
                    if (destroyed) {
                        if (mutableState.value !is VpnState.Error) mutableState.value = VpnState.Disconnected
                    } else if (failure != null && !stopRequested) {
                        finishWithError(failure!!)
                    }
                }
            }
        }
    }

    private fun foreground(message: String) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(message, ongoing = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else { 0 },
        )
    }

    private fun finishWithError(message: String) {
        mutableState.value = VpnState.Error(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(message, ongoing = false))
            }
        } catch (_: SecurityException) {
            // Revoking notifications races with delivery; the UI still observes the error.
        } finally { stopSelf() }
    }

    private fun buildNotification(message: String, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message).setContentIntent(openApp)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOnlyAlertOnce(true)
            .setOngoing(ongoing).setAutoCancel(!ongoing)
        if (ongoing) {
            val disconnect = PendingIntent.getService(
                this, 0, Intent(this, WotVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_power, getString(R.string.vpn_disconnect), disconnect)
        }
        return builder.build()
    }

    private fun stopConnection() {
        if (stopJob != null) return
        stopRequested = true
        mutableState.value = VpnState.Stopping
        val active = connectionJob
        stopJob = scope.launch(start = CoroutineStart.LAZY) {
            active?.cancelAndJoin()
            mutableState.value = VpnState.Disconnected
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
            stopJob = null
        }
        stopJob?.start()
    }

    override fun onRevoke() {
        stopConnection()
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        stopRequested = true
        if (mutableState.value !is VpnState.Error) {
            mutableState.value = if (connectionJob != null) VpnState.Stopping else VpnState.Disconnected
        }
        scope.cancel() // The connection job's NonCancellable finally still closes both native workers and all fds.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.dronits.wotvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.dronits.wotvpn.action.DISCONNECT"
        private const val EXTRA_PROXY_CONFIG = "com.dronits.wotvpn.extra.PROXY_CONFIG"
        private const val NOTIFICATION_CHANNEL = "wot_vpn_status"
        private const val NOTIFICATION_ID = 1
        private val mutableState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = mutableState.asStateFlow()

        internal fun requestConnect(context: Context, configuration: String) {
            if (state.value == VpnState.Preparing || state.value == VpnState.Connected || state.value == VpnState.Stopping) return
            mutableState.value = VpnState.Preparing
            try {
                checkNotNull(context.startForegroundService(
                    Intent(context, WotVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROXY_CONFIG, configuration),
                )) { "VPN service unavailable" }
            } catch (exception: RuntimeException) {
                mutableState.value = VpnState.Error(context.getString(R.string.error_vpn_start))
                throw exception
            }
        }

        internal fun requestDisconnect(context: Context) {
            checkNotNull(context.startService(Intent(context, WotVpnService::class.java).setAction(ACTION_DISCONNECT))) {
                "VPN service unavailable"
            }
        }
    }
}
