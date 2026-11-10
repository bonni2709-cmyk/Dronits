package com.dronits.wotvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronits.wotvpn.R
import com.dronits.wotvpn.config.messageRes
import com.dronits.wotvpn.ui.theme.WotVpnTheme
import com.dronits.wotvpn.vpn.VpnState
import com.dronits.wotvpn.vpn.WotGame

@Composable
internal fun MainScreen(
    state: MainUiState,
    onConfigurationChanged: (String) -> Unit,
    onConnectionClick: () -> Unit,
    onCancelRequest: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AppHeader()
                GameCard(state.gameInstalled)
                ConnectionStatus(state)
                ConfigurationCard(state, onConfigurationChanged)

                Button(
                    onClick = {
                        keyboard?.hide()
                        onConnectionClick()
                    },
                    enabled = state.vpnState != VpnState.Stopping && (state.vpnActive ||
                        (!state.permissionRequestInProgress && state.gameInstalled)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = if (state.vpnActive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    if (state.permissionRequestInProgress || state.vpnState == VpnState.Preparing || state.vpnState == VpnState.Stopping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = if (state.vpnActive) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    } else {
                        Icon(painterResource(R.drawable.ic_power), contentDescription = null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(
                            when {
                                state.vpnState == VpnState.Stopping -> R.string.status_stopping
                                state.vpnActive -> R.string.vpn_disconnect
                                state.permissionRequestInProgress -> R.string.status_preparing
                                else -> R.string.connect
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (state.permissionRequestInProgress) {
                    TextButton(onClick = onCancelRequest, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.cancel_request))
                    }
                }

                if (!state.notificationsEnabled) {
                    NotificationNotice(onNotificationSettings, enabled = !state.permissionRequestInProgress)
                }
                EngineNotice()
                Text(
                    stringResource(R.string.config_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier.size(52.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_vpn),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(13.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.header_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun GameCard(installed: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (installed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.game_scope_title), style = MaterialTheme.typography.titleSmall)
            Text(
                WotGame.PACKAGE_NAME,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (installed) R.string.game_installed else R.string.error_game_not_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!installed) {
                Text(stringResource(R.string.game_missing_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectionStatus(state: MainUiState) {
    val color = statusColor(state)
    Column(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(statusTitle(state), style = MaterialTheme.typography.titleLarge, color = color)
        }
        Text(
            statusDescription(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigurationCard(state: MainUiState, onChanged: (String) -> Unit) {
    var showConfiguration by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.config_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.configuration,
                onValueChange = onChanged,
                enabled = state.configurationEditable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.config_label)) },
                placeholder = { Text(stringResource(R.string.config_placeholder)) },
                minLines = 3,
                maxLines = 6,
                isError = state.configurationError != null,
                visualTransformation = if (showConfiguration) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                supportingText = {
                    Text(
                        state.configurationError?.let { stringResource(it.messageRes) }
                            ?: stringResource(R.string.config_supported),
                    )
                },
                trailingIcon = {
                    TextButton(onClick = { showConfiguration = !showConfiguration }) {
                        Text(stringResource(if (showConfiguration) R.string.config_hide else R.string.config_show))
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.config_single_link_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onChanged("") },
                    enabled = state.configurationEditable && state.configuration.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.config_clear))
                }
            }
        }
    }
}

@Composable
private fun NotificationNotice(onSettings: () -> Unit, enabled: Boolean) {
    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.notifications_off_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSettings, enabled = enabled) {
                Text(stringResource(R.string.notification_settings))
            }
        }
    }
}

@Composable
private fun EngineNotice() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.stage_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.stage_description), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.udp_requirement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusColor(state: MainUiState): Color = when {
    state.permissionRequestInProgress || state.vpnState == VpnState.Preparing || state.vpnState == VpnState.Stopping -> MaterialTheme.colorScheme.secondary
    state.vpnState == VpnState.Connected -> MaterialTheme.colorScheme.primary
    state.actionError != null || state.vpnState is VpnState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun statusTitle(state: MainUiState): String = stringResource(
    when {
        state.vpnState == VpnState.Stopping -> R.string.status_stopping
        state.permissionRequestInProgress -> R.string.status_permissions
        state.vpnState == VpnState.Preparing -> R.string.status_preparing
        state.vpnState == VpnState.Connected -> R.string.status_connected
        state.actionError != null || state.vpnState is VpnState.Error -> R.string.status_error
        else -> R.string.status_disconnected
    },
)

@Composable
private fun statusDescription(state: MainUiState): String = when {
    state.vpnState == VpnState.Stopping -> stringResource(R.string.status_stopping_description)
    state.permissionRequestInProgress -> stringResource(R.string.status_permissions_description)
    state.actionError != null -> stringResource(state.actionError.messageRes)
    state.vpnState == VpnState.Preparing -> stringResource(R.string.status_preparing_description)
    state.vpnState is VpnState.Error -> state.vpnState.message
    state.vpnState == VpnState.Connected -> stringResource(R.string.status_connected_description)
    else -> stringResource(R.string.status_disconnected_description)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
private fun MainScreenPreview() {
    WotVpnTheme {
        MainScreen(
            state = MainUiState(gameInstalled = true),
            onConfigurationChanged = {},
            onConnectionClick = {},
            onCancelRequest = {},
            onNotificationSettings = {},
        )
    }
}
