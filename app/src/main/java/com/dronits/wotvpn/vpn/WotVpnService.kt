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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dronits.wotvpn.R
import com.dronits.wotvpn.config.ProxyConfigInput
import com.dronits.wotvpn.config.messageRes
import com.dronits.wotvpn.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WotVpnService : VpnService() {
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.vpn_notification_channel_description)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> prepareConnection(intent?.getStringExtra(EXTRA_PROXY_CONFIG))
            ACTION_DISCONNECT -> disconnect()
            else -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun prepareConnection(configuration: String?) {
        mutableState.value = VpnState.Preparing

        try {
            if (!WotGame.isInstalled(this)) {
                finishWithError(getString(R.string.error_game_not_installed))
                return
            }
            val inputError = ProxyConfigInput.validate(configuration.orEmpty())
            if (inputError != null) {
                finishWithError(getString(inputError.messageRes))
                return
            }

            // Validate the policy, but never establish a TUN without a packet relay.
            // addAllowedApplication also catches removal of the game after the initial check.
            GameTunnelPolicy.configure(Builder(), getString(R.string.app_name))

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.vpn_preparing), ongoing = true),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )

            if (VpnService.prepare(this) != null) {
                finishWithError(getString(R.string.error_vpn_permission))
                return
            }

            // Reject startup until a packet relay exists; a configured builder is not a working VPN.
            finishWithError(getString(R.string.error_core_unavailable))
        } catch (_: PackageManager.NameNotFoundException) {
            finishWithError(getString(R.string.error_game_not_installed))
        } catch (exception: SecurityException) {
            Log.w(TAG, "VPN start denied", exception)
            finishWithError(getString(R.string.error_vpn_start))
        } catch (exception: RuntimeException) {
            Log.e(TAG, "VPN preparation failed", exception)
            finishWithError(getString(R.string.error_vpn_start))
        }
    }

    private fun finishWithError(message: String) {
        mutableState.value = VpnState.Error(message)
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(message, ongoing = false),
            )
        }
        stopSelf()
    }

    private fun buildNotification(message: String, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openApp)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(
                if (ongoing) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_ERROR,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (ongoing) {
            val disconnectIntent = PendingIntent.getService(
                this,
                0,
                Intent(this, WotVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                R.drawable.ic_vpn,
                getString(R.string.vpn_disconnect),
                disconnectIntent,
            )
        }
        return builder.build()
    }

    private fun disconnect() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
        mutableState.value = VpnState.Disconnected
        stopSelf()
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (mutableState.value !is VpnState.Error) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            mutableState.value = VpnState.Disconnected
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.dronits.wotvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.dronits.wotvpn.action.DISCONNECT"

        private const val EXTRA_PROXY_CONFIG = "com.dronits.wotvpn.extra.PROXY_CONFIG"
        private const val TAG = "WotVpnService"
        private const val NOTIFICATION_CHANNEL = "wot_vpn_status"
        private const val NOTIFICATION_ID = 1

        private val mutableState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = mutableState.asStateFlow()

        internal fun requestConnect(context: Context, configuration: String) {
            if (state.value == VpnState.Preparing || state.value == VpnState.Connected) return
            mutableState.value = VpnState.Preparing
            try {
                checkNotNull(
                    context.startForegroundService(
                        Intent(context, WotVpnService::class.java)
                            .setAction(ACTION_CONNECT)
                            .putExtra(EXTRA_PROXY_CONFIG, configuration),
                    ),
                ) { "VPN service unavailable" }
            } catch (exception: RuntimeException) {
                mutableState.value = VpnState.Error(context.getString(R.string.error_vpn_start))
                throw exception
            }
        }

        internal fun requestDisconnect(context: Context) {
            checkNotNull(
                context.startService(
                    Intent(context, WotVpnService::class.java).setAction(ACTION_DISCONNECT),
                ),
            ) { "VPN service unavailable" }
        }
    }
}
