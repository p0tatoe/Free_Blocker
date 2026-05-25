package dev.michaelylee.freeblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.michaelylee.freeblocker.data.BlocklistState

@Composable
fun BlockedWebsitesScreen(
    viewModel: VpnViewModel,
    onRequestVpnPermission: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVpnEnabled      by viewModel.isVpnEnabled.collectAsState()
    val isBlockingEnabled by viewModel.isBlockingEnabled.collectAsState()
    val isStartOnBoot     by viewModel.isStartOnBoot.collectAsState()
    val blocklistState    by viewModel.blocklistState.collectAsState()
    val pendingRestart    by viewModel.pendingRestartReason.collectAsState()
    val upstream          by viewModel.upstreamConfig.collectAsState()
    val manualBlocked     by viewModel.manualBlockedDomains.collectAsState()
    val snackbarHost      = remember { SnackbarHostState() }

    LaunchedEffect(pendingRestart) {
        if (pendingRestart != null) {
            val result = snackbarHost.showSnackbar(
                message     = "Restart VPN to apply changes",
                actionLabel = "Restart",
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restartVpn()
            else viewModel.dismissRestartBanner()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { scaffoldPadding ->
        LazyColumn(
            modifier            = modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            // ── Status card ───────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                VpnStatusCard(
                    isVpnEnabled      = isVpnEnabled,
                    isBlockingEnabled = isBlockingEnabled,
                    isStartOnBoot     = isStartOnBoot,
                    blocklistState    = blocklistState,
                    upstreamHost      = upstream.host,
                    onVpnToggle       = {
                        if (!isVpnEnabled) onRequestVpnPermission()
                        else viewModel.setVpnEnabled(false)
                    },
                    onBlockingToggle  = { viewModel.setBlockingEnabled(it) },
                    onStartOnBootToggle = { viewModel.setStartOnBoot(it) },
                    onCloseApp        = onCloseApp,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Manual blocked domains ────────────────────────────────────────
            item {
                Text(
                    text     = "Blocked Websites",
                    style    = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                DomainInputRow(
                    placeholder = "e.g. ads.example.com",
                    onAdd       = { viewModel.addManualBlockedDomain(it) },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (manualBlocked.isEmpty()) {
                item {
                    Text(
                        text  = "No websites blocked manually yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(manualBlocked.toList(), key = { "blocked_$it" }) { domain ->
                DomainRow(
                    label    = domain,
                    onDelete = { viewModel.removeManualBlockedDomain(domain) },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── VPN status card ───────────────────────────────────────────────────────────

@Composable
private fun VpnStatusCard(
    isVpnEnabled        : Boolean,
    isBlockingEnabled   : Boolean,
    isStartOnBoot       : Boolean,
    blocklistState      : BlocklistState,
    upstreamHost        : String,
    onVpnToggle         : () -> Unit,
    onBlockingToggle    : (Boolean) -> Unit,
    onStartOnBootToggle : (Boolean) -> Unit,
    onCloseApp          : () -> Unit,
) {
    val containerColor = when {
        isVpnEnabled && isBlockingEnabled -> MaterialTheme.colorScheme.primaryContainer
        isVpnEnabled                      -> MaterialTheme.colorScheme.secondaryContainer
        else                              -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── VPN on/off row ────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text  = when {
                            isVpnEnabled && isBlockingEnabled -> "Blocking active"
                            isVpnEnabled                      -> "VPN on · blocking paused"
                            else                              -> "VPN off"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text  = "DoQ+DoH · $upstreamHost",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked         = isVpnEnabled,
                    onCheckedChange = { onVpnToggle() },
                )
            }

            // ── Blocking enabled row (only shown when VPN is on) ──────────────
            if (isVpnEnabled) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text(
                            text  = "Blocking enabled",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text  = "Uncheck to pause filtering without stopping VPN",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked         = isBlockingEnabled,
                        onCheckedChange = onBlockingToggle,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Spacer(Modifier.height(12.dp))

            // ── Start on boot row ─────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text  = "Start on device boot",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked         = isStartOnBoot,
                    onCheckedChange = onStartOnBootToggle,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Spacer(Modifier.height(12.dp))

            // ── Blocklist state ───────────────────────────────────────────────
            val stateLabel = when (val s = blocklistState) {
                is BlocklistState.Idle    -> "Blocklist not loaded"
                is BlocklistState.Loading -> "Updating blocklist…"
                is BlocklistState.Success -> "${s.totalDomains} domains blocked"
                is BlocklistState.Error   -> "⚠ Blocklist error: ${s.message}"
            }
            Text(
                text  = stateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // ── Close app button ──────────────────────────────────────────────
            OutlinedButton(
                onClick  = onCloseApp,
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector         = Icons.Default.Close,
                    contentDescription  = null,
                    modifier            = Modifier.padding(end = 8.dp),
                )
                Text("Stop VPN & Close")
            }
        }
    }
}

// ── Shared sub-composables ────────────────────────────────────────────────────

@Composable
internal fun DomainInputRow(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value         = text,
            onValueChange = { text = it },
            placeholder   = { Text(placeholder) },
            singleLine    = true,
            modifier      = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text.trim())
                    text = ""
                }
            },
        ) {
            Text("Add")
        }
    }
}

@Composable
internal fun DomainRow(label: String, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Remove")
        }
    }
}