package com.carcassonne.lan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carcassonne.lan.model.MatchStatus

@Composable
fun CarcassonneAppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    val colorScheme = MaterialTheme.colorScheme.copy(
        primary = Color(0xFFA6402A),
        secondary = Color(0xFF4A6548),
        background = Color(0xFFF2EFE5),
        surface = Color(0xFFFBF8EE),
    )

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (state.isBootstrapping) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Preparing LAN game...")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    TopTabs(
                        selected = state.tab,
                        onSelect = vm::selectTab,
                    )

                    when (state.tab) {
                        AppTab.LOBBY -> LobbyScreen(
                            state = state,
                            onRefresh = vm::refreshLobbyNow,
                            onConnectSelf = vm::connectToSelfHost,
                            onInviteHost = vm::inviteHost,
                            onAcceptInvite = vm::acceptInvite,
                            onDeclineInvite = vm::declineInvite,
                        )

                        AppTab.MATCH -> MatchScreen(
                            state = state,
                            onTapCell = vm::onBoardTap,
                            onLongPressCell = vm::onBoardLongPress,
                            onTapMeepleOption = vm::onMeepleOptionTap,
                            onConfirmPlacement = vm::confirmLockedPlacement,
                            onRevertPlacement = vm::revertLockedPlacement,
                            onDisconnect = vm::disconnectSession,
                        )

                        AppTab.SETTINGS -> SettingsScreen(
                            state = state,
                            onSave = vm::saveSettings,
                            onRefreshLocalIps = vm::refreshLocalNetworkInfo,
                            onProbeIp = vm::probeSelectedIp,
                        )
                    }

                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopTabs(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabButton("Lobby", selected == AppTab.LOBBY) { onSelect(AppTab.LOBBY) }
        TabButton("Match", selected == AppTab.MATCH) { onSelect(AppTab.MATCH) }
        TabButton("Settings", selected == AppTab.SETTINGS) { onSelect(AppTab.SETTINGS) }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun LobbyScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onConnectSelf: () -> Unit,
    onInviteHost: (HostCard) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRefresh) {
                Text("Refresh LAN")
            }
            OutlinedButton(onClick = onConnectSelf) {
                Text("Connect Self")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.outgoingInvite != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Outgoing invite", fontWeight = FontWeight.Bold)
                    Text("To: ${state.outgoingInvite.targetHostName} (${state.outgoingInvite.targetAddress}:${state.outgoingInvite.targetPort})")
                    Text("Waiting for accept/decline...")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.incomingInvites.isNotEmpty()) {
            Text("Incoming invites", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.incomingInvites.forEach { invite ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${invite.fromName} invited you")
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAcceptInvite(invite.inviteId) }) {
                                    Text("Accept")
                                }
                                OutlinedButton(onClick = { onDeclineInvite(invite.inviteId) }) {
                                    Text("Decline")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (state.hosts.isEmpty()) {
            Text("No other players discovered on port ${state.settings.port}.")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.hosts) { host ->
                HostCardView(
                    host = host,
                    invitePending = state.outgoingInvite != null,
                    onInvite = { onInviteHost(host) },
                )
            }
        }
    }
}

@Composable
private fun HostCardView(
    host: HostCard,
    invitePending: Boolean,
    onInvite: () -> Unit,
) {
    val available = host.ping.openSlots > 0 && host.ping.matchStatus == MatchStatus.WAITING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${host.address}:${host.port}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Player: ${host.ping.hostName}")
            Text("Status: ${if (available) "Available" else "Unavailable"}")
            if (host.ping.players.isNotEmpty()) {
                Text(
                    host.ping.players.joinToString(prefix = "Players: ") {
                        "P${it.player} ${it.name}${if (!it.connected) " (offline)" else ""}"
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (available) {
                Button(
                    onClick = onInvite,
                    enabled = !invitePending,
                ) {
                    Text("Invite")
                }
            } else {
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("Unavailable")
                }
            }
        }
    }
}

@Composable
private fun MatchScreen(
    state: AppUiState,
    onTapCell: (Int, Int) -> Unit,
    onLongPressCell: (Int, Int) -> Unit,
    onTapMeepleOption: (String) -> Unit,
    onConfirmPlacement: () -> Unit,
    onRevertPlacement: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val match = state.match
    val session = state.session

    if (session == null || match == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Connect to a host from Lobby.")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        val p1 = match.players[1]
        val p2 = match.players[2]
        Text(
            text = "${p1?.name ?: "P1"} ${match.score[1] ?: 0} - ${match.score[2] ?: 0} ${p2?.name ?: "Waiting"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (match.status == MatchStatus.ACTIVE) {
                "Turn ${match.turnState.turnIndex}: P${match.turnState.player} | tile ${match.turnState.tileId ?: "-"}"
            } else {
                "Match status: ${match.status.name}"
            }
        )
        Text(
            text = if (state.canAct) {
                "Tap to preview/rotate. Long-press to lock tile. Tap marker for meeple. Confirm/Revert near tile."
            } else {
                "Waiting. You can still pan/zoom the board."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(modifier = Modifier.height(8.dp))

        BoardView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            match = match,
            preview = state.preview,
            lockedPlacement = state.lockedPlacement,
            onTapCell = onTapCell,
            onLongPressCell = onLongPressCell,
            onTapMeepleOption = onTapMeepleOption,
            onConfirmPlacement = onConfirmPlacement,
            onRevertPlacement = onRevertPlacement,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
            Text(
                text = "You: ${session.playerName} (P${session.player})",
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onSave: (String, String) -> Unit,
    onRefreshLocalIps: () -> Unit,
    onProbeIp: (String) -> Unit,
) {
    var playerName by remember(state.settings.playerName) { mutableStateOf(state.settings.playerName) }
    var port by remember(state.settings.port) { mutableStateOf(state.settings.port.toString()) }
    var probeTargetIp by remember { mutableStateOf("") }

    LaunchedEffect(state.settings.playerName, state.settings.port) {
        playerName = state.settings.playerName
        port = state.settings.port.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("Player name always keeps a numeric suffix for reconnect identity.")

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = playerName,
            onValueChange = { playerName = it },
            label = { Text("Player name") },
            singleLine = true,
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = port,
            onValueChange = { raw ->
                port = raw.filter { it.isDigit() }.take(5)
            },
            label = { Text("LAN port") },
            singleLine = true,
        )

        Text("Default port is 18473. Change if the network uses a conflicting service.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(playerName, port) }) {
                Text("Save")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Current host endpoint: 0.0.0.0:${state.settings.port}",
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Network tools",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        val localIpsText = if (state.localIpAddresses.isEmpty()) {
            "No local IPv4 detected."
        } else {
            state.localIpAddresses.joinToString()
        }
        Text("Device IPv4: $localIpsText")

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = probeTargetIp,
            onValueChange = { probeTargetIp = it.trim() },
            label = { Text("Check IP address") },
            placeholder = { Text("e.g. 192.168.1.42") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefreshLocalIps) {
                Text("Refresh IPs")
            }
            Button(
                onClick = { onProbeIp(probeTargetIp) },
                enabled = !state.manualProbeBusy,
            ) {
                Text(if (state.manualProbeBusy) "Checking..." else "Ping IP")
            }
        }

        Text(
            text = if (state.manualProbeResult.isBlank()) {
                "Ping checks reachability and whether this app responds on the configured LAN port."
            } else {
                state.manualProbeResult
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
