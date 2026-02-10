package com.carcassonne.lan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    if (state.tab != AppTab.MATCH) {
                        TopTabs(
                            selected = state.tab,
                            onSelect = vm::selectTab,
                        )
                    }

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
                            onSelectTab = vm::selectTab,
                            onTapCell = vm::onBoardTap,
                            onLongPressCell = vm::onBoardLongPress,
                            onTapMeepleOption = vm::onMeepleOptionTap,
                            onTapInspectOption = vm::onInspectOptionTap,
                            onClearInspectSelection = vm::clearInspectSelection,
                            onConfirmPlacement = vm::confirmLockedPlacement,
                            onRevertPlacement = vm::revertLockedPlacement,
                            onSelectScoreGroup = vm::selectScoreGroup,
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

                if (state.isBootstrapping) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp),
                        color = Color(0xD9FFF6D9),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Preparing LAN game...")
                        }
                    }
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
    onSelectTab: (AppTab) -> Unit,
    onTapCell: (Int, Int) -> Unit,
    onLongPressCell: (Int, Int) -> Unit,
    onTapMeepleOption: (String) -> Unit,
    onTapInspectOption: (String) -> Unit,
    onClearInspectSelection: () -> Unit,
    onConfirmPlacement: () -> Unit,
    onRevertPlacement: () -> Unit,
    onSelectScoreGroup: (String?) -> Unit,
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

    val p1 = match.players[1]
    val p2 = match.players[2]
    val remainingTiles = match.remaining.values.sumOf { it.coerceAtLeast(0) }
    val p1Meeples = match.meeplesAvailable[1] ?: 0
    val p2Meeples = match.meeplesAvailable[2] ?: 0
    var topPanelExpanded by rememberSaveable { mutableStateOf(true) }
    var scorePanelExpanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BoardView(
            modifier = Modifier.fillMaxSize(),
            match = match,
            viewerPlayer = session.player,
            activePlayer = match.turnState.player,
            simplifiedView = state.settings.simplifiedView,
            tileVisuals = state.tileVisuals,
            boardMeeples = state.boardMeeples,
            selectedScoreHighlights = state.selectedScoreHighlights,
            preview = state.preview,
            lockedPlacement = state.lockedPlacement,
            inspectSelection = state.inspectSelection,
            onTapCell = onTapCell,
            onLongPressCell = onLongPressCell,
            onTapMeepleOption = onTapMeepleOption,
            onTapInspectOption = onTapInspectOption,
            onClearInspectSelection = onClearInspectSelection,
            onConfirmPlacement = onConfirmPlacement,
            onRevertPlacement = onRevertPlacement,
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PanelToggleButton(
                    expanded = topPanelExpanded,
                    expandWhenCollapsedUp = false,
                    contentDescription = if (topPanelExpanded) "Hide top panel" else "Show top panel",
                    onToggle = { topPanelExpanded = !topPanelExpanded },
                )
            }

            if (topPanelExpanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    color = Color(0x9EFFFFFF),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { onSelectTab(AppTab.LOBBY) }) {
                                Text("Lobby")
                            }
                            OutlinedButton(onClick = { onSelectTab(AppTab.SETTINGS) }) {
                                Text("Settings")
                            }
                            OutlinedButton(onClick = onDisconnect) {
                                Text("Disconnect")
                            }
                        }
                        Text(
                            text = "${p1?.name ?: "P1"} ${match.score[1] ?: 0} - ${match.score[2] ?: 0} ${p2?.name ?: "Waiting"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (match.status == MatchStatus.ACTIVE) {
                                "Turn ${match.turnState.turnIndex}: P${match.turnState.player} | tile ${match.turnState.tileId ?: "-"}"
                            } else {
                                "Match status: ${match.status.name}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = if (state.canAct) {
                                "Tap to place/rotate. Invalid spots stay visible with a cross. Long-press to lock."
                            } else {
                                "Waiting. You can still pan and zoom."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "You: ${session.playerName} (P${session.player})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            MatchResourceStrip(
                p1Meeples = p1Meeples,
                p2Meeples = p2Meeples,
                remainingTiles = remainingTiles,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (scorePanelExpanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    color = Color(0x86FFFFFF),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    ScorePanel(
                        groups = state.scoreGroups,
                        currentScore = match.score,
                        projectedFinalScore = state.projectedFinalScore,
                        selectedKey = state.selectedScoreGroupKey,
                        onSelect = onSelectScoreGroup,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            PanelToggleButton(
                expanded = scorePanelExpanded,
                expandWhenCollapsedUp = true,
                contentDescription = if (scorePanelExpanded) "Hide scoreboard" else "Show scoreboard",
                onToggle = { scorePanelExpanded = !scorePanelExpanded },
            )
        }
    }
}

@Composable
private fun ScorePanel(
    groups: List<ScoreGroupState>,
    currentScore: Map<Int, Int>,
    projectedFinalScore: Map<Int, Int>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    val openGroups = groups.filter { !it.closedScored }
    val closedGroups = groups.filter { it.closedScored }
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(
            "Score P1:${currentScore[1] ?: 0}|P2:${currentScore[2] ?: 0} / " +
                "Projection P1:${projectedFinalScore[1] ?: 0}|P2:${projectedFinalScore[2] ?: 0}",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
        )
        if (groups.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("No active scoring areas yet.", style = MaterialTheme.typography.bodySmall)
            return
        }

        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(openGroups) { group ->
                ScoreGroupRow(
                    group = group,
                    selected = selectedKey == group.key,
                    onClick = { onSelect(group.key) },
                )
            }
            if (closedGroups.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(2.dp))
                    HorizontalDivider(color = Color(0x66444444), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Closed",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            items(closedGroups) { group ->
                ScoreGroupRow(
                    group = group,
                    selected = selectedKey == group.key,
                    onClick = { onSelect(group.key) },
                )
            }
        }
    }
}

@Composable
private fun ScoreGroupRow(
    group: ScoreGroupState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = "${group.label} P1:${group.p1EndNowScore}|P2:${group.p2EndNowScore}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MatchResourceStrip(
    p1Meeples: Int,
    p2Meeples: Int,
    remainingTiles: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeepleCounterDot(
            color = Color(0xFF2B6BE1),
            label = "P1",
            count = p1Meeples,
        )
        Spacer(modifier = Modifier.width(16.dp))
        MeepleCounterDot(
            color = Color(0xFFD53E3E),
            label = "P2",
            count = p2Meeples,
        )
        Spacer(modifier = Modifier.width(18.dp))
        TileCounterSquare(remainingTiles = remainingTiles)
    }
}

@Composable
private fun MeepleCounterDot(
    color: Color,
    label: String,
    count: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
        )
    }
}

@Composable
private fun TileCounterSquare(remainingTiles: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .border(width = 1.4.dp, color = Color(0xFF463019)),
        )
        Text(
            text = "$remainingTiles",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
        )
    }
}

@Composable
private fun PanelToggleButton(
    expanded: Boolean,
    expandWhenCollapsedUp: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
) {
    val icon = when {
        expanded && expandWhenCollapsedUp -> Icons.Filled.ExpandMore
        expanded && !expandWhenCollapsedUp -> Icons.Filled.ExpandLess
        !expanded && expandWhenCollapsedUp -> Icons.Filled.ExpandLess
        else -> Icons.Filled.ExpandMore
    }
    Surface(
        shape = CircleShape,
        color = Color(0x96FFFFFF),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color(0xFF1E1E1E),
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onSave: (String, String, Boolean) -> Unit,
    onRefreshLocalIps: () -> Unit,
    onProbeIp: (String) -> Unit,
) {
    var playerName by remember(state.settings.playerName) { mutableStateOf(state.settings.playerName) }
    var port by remember(state.settings.port) { mutableStateOf(state.settings.port.toString()) }
    var simplifiedView by remember(state.settings.simplifiedView) { mutableStateOf(state.settings.simplifiedView) }
    var probeTargetIp by remember { mutableStateOf("") }

    LaunchedEffect(state.settings.playerName, state.settings.port, state.settings.simplifiedView) {
        playerName = state.settings.playerName
        port = state.settings.port.toString()
        simplifiedView = state.settings.simplifiedView
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = simplifiedView,
                onCheckedChange = { simplifiedView = it },
            )
            Text("Simplified view")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(playerName, port, simplifiedView) }) {
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
