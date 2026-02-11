package com.carcassonne.lan.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carcassonne.lan.model.GameMode
import com.carcassonne.lan.model.GameRules
import com.carcassonne.lan.model.MatchState
import com.carcassonne.lan.model.MatchStatus
import com.carcassonne.lan.model.ParallelPhase
import com.carcassonne.lan.model.ParallelRoundState
import kotlinx.coroutines.delay

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
                            onSoloPlay = vm::startSoloPlay,
                            onApplyGameRules = vm::updateLobbyGameRules,
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
                            onPickParallelTile = vm::pickParallelTile,
                            onResolveParallelConflict = vm::resolveParallelConflict,
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
    onSoloPlay: () -> Unit,
    onApplyGameRules: (GameRules) -> Unit,
    onInviteHost: (HostCard) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
) {
    var meeplesText by rememberSaveable(state.lobbyRules.meeplesPerPlayer) {
        mutableStateOf(state.lobbyRules.meeplesPerPlayer.toString())
    }
    var smallCityFourPoints by rememberSaveable(state.lobbyRules.smallCityTwoTilesFourPoints) {
        mutableStateOf(state.lobbyRules.smallCityTwoTilesFourPoints)
    }
    var gameMode by rememberSaveable(state.lobbyRules.gameMode) {
        mutableStateOf(state.lobbyRules.gameMode)
    }
    var randomizedMovesText by rememberSaveable(state.lobbyRules.randomizedMoveLimit) {
        mutableStateOf(state.lobbyRules.randomizedMoveLimit.toString())
    }
    var previewEnabled by rememberSaveable(state.lobbyRules.previewEnabled) {
        mutableStateOf(state.lobbyRules.previewEnabled)
    }
    var previewCountText by rememberSaveable(state.lobbyRules.previewCount) {
        mutableStateOf(state.lobbyRules.previewCount.toString())
    }
    var parallelSelectionText by rememberSaveable(state.lobbyRules.parallelSelectionSize) {
        mutableStateOf(state.lobbyRules.parallelSelectionSize.toString())
    }
    var parallelMovesText by rememberSaveable(state.lobbyRules.parallelMoveLimit) {
        mutableStateOf(state.lobbyRules.parallelMoveLimit.toString())
    }

    fun emitRules() {
        val current = state.lobbyRules
        val rules = GameRules(
            gameMode = gameMode,
            meeplesPerPlayer = meeplesText.toIntOrNull() ?: current.meeplesPerPlayer,
            smallCityTwoTilesFourPoints = smallCityFourPoints,
            randomizedMode = gameMode == GameMode.RANDOM,
            randomizedMoveLimit = randomizedMovesText.toIntOrNull() ?: current.randomizedMoveLimit,
            previewEnabled = if (gameMode == GameMode.PARALLEL) false else previewEnabled,
            previewCount = previewCountText.toIntOrNull() ?: current.previewCount,
            parallelSelectionSize = parallelSelectionText.toIntOrNull() ?: current.parallelSelectionSize,
            parallelMoveLimit = parallelMovesText.toIntOrNull() ?: current.parallelMoveLimit,
        )
        onApplyGameRules(rules)
    }

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
            OutlinedButton(onClick = onSoloPlay) {
                Text("Solo Play")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Game settings", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = meeplesText,
                    onValueChange = { raw ->
                        val next = raw.filter { c -> c.isDigit() }.take(2)
                        meeplesText = next
                        emitRules()
                    },
                    label = { Text("Meeples per player") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Mode", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(
                        GameMode.STANDARD to "Standard",
                        GameMode.RANDOM to "Random",
                        GameMode.PARALLEL to "Parallel",
                    )
                    modes.forEach { (mode, label) ->
                        if (gameMode == mode) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    gameMode = mode
                                    emitRules()
                                },
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = smallCityFourPoints,
                        onCheckedChange = { checked ->
                            smallCityFourPoints = checked
                            emitRules()
                        },
                    )
                    Text("2-tile city = 4 points (off => 2 points)")
                }

                if (gameMode == GameMode.RANDOM) {
                    OutlinedTextField(
                        value = randomizedMovesText,
                        onValueChange = { raw ->
                            val next = raw.filter { c -> c.isDigit() }.take(3)
                            randomizedMovesText = next
                            emitRules()
                        },
                        label = { Text("Move limit") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (gameMode == GameMode.PARALLEL) {
                    OutlinedTextField(
                        value = parallelSelectionText,
                        onValueChange = { raw ->
                            val next = raw.filter { c -> c.isDigit() }.take(1)
                            parallelSelectionText = next
                            emitRules()
                        },
                        label = { Text("Tile choices per round (1-6)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = parallelMovesText,
                        onValueChange = { raw ->
                            val next = raw.filter { c -> c.isDigit() }.take(3)
                            parallelMovesText = next
                            emitRules()
                        },
                        label = { Text("Parallel rounds") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = previewEnabled,
                            onCheckedChange = { checked ->
                                previewEnabled = checked
                                emitRules()
                            },
                        )
                        Text("Preview upcoming tiles")
                    }
                }

                if (gameMode != GameMode.PARALLEL && previewEnabled) {
                    OutlinedTextField(
                        value = previewCountText,
                        onValueChange = { raw ->
                            val next = raw.filter { c -> c.isDigit() }.take(2)
                            previewCountText = next
                            emitRules()
                        },
                        label = { Text("Preview tile count (N)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.outgoingInvite != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Outgoing invite", fontWeight = FontWeight.Bold)
                    Text("To: ${state.outgoingInvite.targetHostName} (${state.outgoingInvite.targetAddress}:${state.outgoingInvite.targetPort})")
                    Text("Rules: ${gameRulesSummary(state.outgoingInvite.targetRules)}")
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
                            Text(
                                text = "Rules: ${gameRulesSummary(invite.rules)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
            Text(
                text = "Rules: ${gameRulesSummary(host.ping.rules)}",
                style = MaterialTheme.typography.bodySmall,
            )
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

private fun gameRulesSummary(rules: GameRules): String {
    val mode = when (rules.gameMode) {
        GameMode.STANDARD -> "standard"
        GameMode.RANDOM -> "random ${rules.randomizedMoveLimit} moves"
        GameMode.PARALLEL -> "parallel ${rules.parallelMoveLimit} rounds, picks ${rules.parallelSelectionSize}"
    }
    val cityText = if (rules.smallCityTwoTilesFourPoints) "2-tile city=4" else "2-tile city=2"
    val previewText = if (rules.previewEnabled) "preview ${rules.previewCount}" else "preview off"
    return "meeples ${rules.meeplesPerPlayer}, $cityText, $mode, $previewText"
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
    onPickParallelTile: (Int) -> Unit,
    onResolveParallelConflict: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val match = state.match
    if (match == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelectTab(AppTab.LOBBY) }) {
                        Text("Lobby")
                    }
                    OutlinedButton(onClick = { onSelectTab(AppTab.SETTINGS) }) {
                        Text("Settings")
                    }
                }
                Text("Connect to a host from Lobby.")
            }
        }
        return
    }
    val session = state.session
    val viewerPlayer = session?.player ?: 1

    val p1 = match.players[1]
    val p2 = match.players[2]
    val isParallel = match.rules.gameMode == GameMode.PARALLEL
    val parallelRound = match.parallelRound
    val remainingTiles = match.remaining.values.sumOf { it.coerceAtLeast(0) }
    val randomizedMode = match.rules.gameMode == GameMode.RANDOM
    val remainingMoves = if (match.status == MatchStatus.ACTIVE) {
        (match.rules.randomizedMoveLimit - (match.turnState.turnIndex - 1)).coerceAtLeast(0)
    } else {
        0
    }
    val remainingRounds = if (isParallel && match.status == MatchStatus.ACTIVE) {
        ((parallelRound?.moveLimit ?: match.rules.parallelMoveLimit) - (parallelRound?.roundIndex ?: 1) + 1).coerceAtLeast(0)
    } else {
        0
    }
    val counterLabel = when {
        isParallel -> "Rounds"
        randomizedMode -> "Moves"
        else -> "Tiles"
    }
    val counterValue = when {
        isParallel -> remainingRounds
        randomizedMode -> remainingMoves
        else -> remainingTiles
    }
    val previewTiles = buildPreviewTiles(
        match = match,
        requested = match.rules.previewCount,
        enabled = match.rules.previewEnabled && !isParallel,
    )
    val p1Meeples = match.meeplesAvailable[1] ?: 0
    val p2Meeples = match.meeplesAvailable[2] ?: 0
    var topPanelExpanded by rememberSaveable { mutableStateOf(true) }
    var scorePanelExpanded by rememberSaveable { mutableStateOf(false) }
    var previewPanelExpanded by rememberSaveable(match.id) { mutableStateOf(true) }
    var centerOverlayMessage by rememberSaveable(match.id) { mutableStateOf<String?>(null) }
    var repositionOverlayShownRound by rememberSaveable(match.id) { mutableStateOf(-1) }

    val placementStamp = parallelRound?.placementDoneAtEpochMs ?: 0L
    LaunchedEffect(placementStamp) {
        if (placementStamp > 0L) {
            val message = "Placement done"
            centerOverlayMessage = message
            delay(1_000L)
            if (centerOverlayMessage == message) {
                centerOverlayMessage = null
            }
        }
    }
    val shouldShowRepositionOverlay = run {
        if (!isParallel || parallelRound?.phase != ParallelPhase.PLACE) {
            false
        } else {
            val me = parallelRound.players[viewerPlayer]
            val othersLocked = parallelRound.players.any { (player, playerState) ->
                player != viewerPlayer && playerState.tileLocked && playerState.intent != null
            }
            me != null &&
                !me.pickedTileId.isNullOrBlank() &&
                !me.tileLocked &&
                othersLocked &&
                match.lastEvent.contains("must place in another place", ignoreCase = true)
        }
    }
    val currentRoundIndex = parallelRound?.roundIndex ?: -1
    LaunchedEffect(shouldShowRepositionOverlay, currentRoundIndex, parallelRound?.phase) {
        if (!shouldShowRepositionOverlay) {
            if (parallelRound?.phase != ParallelPhase.PLACE) {
                repositionOverlayShownRound = -1
            }
            return@LaunchedEffect
        }
        if (repositionOverlayShownRound == currentRoundIndex) {
            return@LaunchedEffect
        }
        val message = "Place tile in another location"
        centerOverlayMessage = message
        repositionOverlayShownRound = currentRoundIndex
        delay(1_100L)
        if (centerOverlayMessage == message) {
            centerOverlayMessage = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BoardView(
            modifier = Modifier.fillMaxSize(),
            match = match,
            viewerPlayer = viewerPlayer,
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
                            if (session != null) {
                                OutlinedButton(onClick = onDisconnect) {
                                    Text("Disconnect")
                                }
                            }
                        }
                        Text(
                            text = "${p1?.name ?: "P1"} ${match.score[1] ?: 0} - ${match.score[2] ?: 0} ${p2?.name ?: "Waiting"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (match.status == MatchStatus.ACTIVE) {
                                if (isParallel && parallelRound != null) {
                                    "Round ${parallelRound.roundIndex}/${parallelRound.moveLimit} | ${parallelRound.phase.name.lowercase()}"
                                } else {
                                    "Turn ${match.turnState.turnIndex}: P${match.turnState.player} | tile ${match.turnState.tileId ?: "-"}"
                                }
                            } else {
                                "Match status: ${match.status.name}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = if (session == null) {
                                "Disconnected. Inspect mode only. Open Lobby to reconnect."
                            } else if (isParallel && parallelRound != null) {
                                when (parallelRound.phase) {
                                    ParallelPhase.PICK -> if (state.canAct) {
                                        "Pick one tile from the strip."
                                    } else {
                                        "Waiting for opponent to pick."
                                    }

                                    ParallelPhase.PLACE -> if (state.canAct) {
                                        "Tap to move/rotate. Long-press to lock placement."
                                    } else {
                                        "Waiting for opponent to lock placement."
                                    }

                                    ParallelPhase.RESOLVE -> if (parallelRound.conflict?.tokenHolder == viewerPlayer) {
                                        "Conflict: choose Retreat or Burn Token."
                                    } else {
                                        "Waiting for priority token decision."
                                    }

                                    ParallelPhase.MEEPLE -> if (state.canAct) {
                                        "Select meeple on your committed tile and confirm."
                                    } else {
                                        "Waiting for opponent meeple confirmation."
                                    }
                                }
                            } else if (state.canAct) {
                                "Tap to place/rotate. Invalid spots stay visible with a cross. Long-press to lock."
                            } else {
                                "Waiting. You can still pan and zoom."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = if (session == null) {
                                "No active session."
                            } else {
                                "You: ${session.playerName} (P${session.player})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            MatchResourceStrip(
                p1Meeples = p1Meeples,
                p2Meeples = p2Meeples,
                counterLabel = counterLabel,
                counterValue = counterValue,
                priorityTokenPlayer = match.priorityTokenPlayer,
            )
            if (isParallel && parallelRound != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PanelToggleButton(
                        expanded = previewPanelExpanded,
                        expandWhenCollapsedUp = false,
                        contentDescription = if (previewPanelExpanded) "Hide tile picks" else "Show tile picks",
                        onToggle = { previewPanelExpanded = !previewPanelExpanded },
                    )
                }
                if (previewPanelExpanded) {
                    Spacer(modifier = Modifier.height(3.dp))
                    ParallelPickStrip(
                        round = parallelRound,
                        viewerPlayer = viewerPlayer,
                        canAct = state.canAct,
                        simplifiedView = state.settings.simplifiedView,
                        tileVisuals = state.tileVisuals,
                        maxHeightPercent = state.settings.previewPaneHeightPercent,
                        onPick = onPickParallelTile,
                    )
                }
                if (parallelRound.phase == ParallelPhase.RESOLVE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ParallelConflictControls(
                        round = parallelRound,
                        viewerPlayer = viewerPlayer,
                        onResolve = onResolveParallelConflict,
                    )
                }
            } else if (match.rules.previewEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PanelToggleButton(
                        expanded = previewPanelExpanded,
                        expandWhenCollapsedUp = false,
                        contentDescription = if (previewPanelExpanded) "Hide preview strip" else "Show preview strip",
                        onToggle = { previewPanelExpanded = !previewPanelExpanded },
                    )
                }
                if (previewPanelExpanded) {
                    Spacer(modifier = Modifier.height(3.dp))
                    UpcomingTileStrip(
                        tileIds = previewTiles,
                        requestedCount = match.rules.previewCount,
                        simplifiedView = state.settings.simplifiedView,
                        tileVisuals = state.tileVisuals,
                        maxHeightPercent = state.settings.previewPaneHeightPercent,
                    )
                }
            }
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

        if (match.status == MatchStatus.FINISHED) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                WinnerBanner(match = match)
            }
        }

        if (!centerOverlayMessage.isNullOrBlank() && isParallel) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                Surface(
                    color = Color(0xC8FFFFFF),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Text(
                        text = centerOverlayMessage ?: "",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E1E1E),
                    )
                }
            }
        }
    }
}

@Composable
private fun WinnerBanner(match: com.carcassonne.lan.model.MatchState) {
    val p1Score = match.score[1] ?: 0
    val p2Score = match.score[2] ?: 0
    val (label, color) = when {
        p1Score > p2Score -> "${match.players[1]?.name ?: "Player 1"} wins!" to Color(0xFF2B6BE1)
        p2Score > p1Score -> "${match.players[2]?.name ?: "Player 2"} wins!" to Color(0xFFD53E3E)
        else -> "Draw!" to Color(0xFF6A5E4B)
    }

    Surface(
        modifier = Modifier
            .padding(horizontal = 22.dp),
        color = Color(0xA6FFFFFF),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
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
    val typeSpecs = listOf(
        "city" to "City",
        "road" to "Road",
        "field" to "Field",
        "cloister" to "Cloister",
    )
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Score", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            ColoredPairNumbers(
                left = currentScore[1] ?: 0,
                right = currentScore[2] ?: 0,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Text("Projection", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            ColoredPairNumbers(
                left = projectedFinalScore[1] ?: 0,
                right = projectedFinalScore[2] ?: 0,
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        if (groups.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("No active scoring areas yet.", style = MaterialTheme.typography.bodySmall)
            return
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((type, title) in typeSpecs) {
                ScoreTypeColumn(
                    modifier = Modifier.weight(1f),
                    type = type,
                    title = title,
                    groups = groups,
                    selectedKey = selectedKey,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun ScoreTypeColumn(
    modifier: Modifier = Modifier,
    type: String,
    title: String,
    groups: List<ScoreGroupState>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    val typed = groups.filter { it.type.equals(type, ignoreCase = true) }
    val open = typed.filter { !it.closedScored }
    val closed = typed.filter { it.closedScored }
    val ordered = open + closed

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        if (ordered.isEmpty()) {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF555555),
            )
            return
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            ordered.forEachIndexed { idx, group ->
                if (idx == open.size && open.isNotEmpty() && closed.isNotEmpty()) {
                    HorizontalDivider(color = Color(0x66444444), thickness = 1.dp)
                }
                ScoreCompactEntry(
                    group = group,
                    index = idx + 1,
                    selected = selectedKey == group.key,
                    onClick = { onSelect(group.key) },
                )
            }
        }
    }
}

@Composable
private fun ScoreCompactEntry(
    group: ScoreGroupState,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val winnerBg = when {
        group.p1EndNowScore > group.p2EndNowScore -> Color(0xFF2B6BE1)
        group.p2EndNowScore > group.p1EndNowScore -> Color(0xFFD53E3E)
        group.p1EndNowScore == group.p2EndNowScore && group.p1EndNowScore > 0 -> Color(0xFF9C6BDA)
        else -> Color(0xFFEDE7D9)
    }.copy(alpha = if (selected) 0.32f else 0.20f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = winnerBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$index(",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            ColoredPairNumbers(
                left = group.meeplesP1,
                right = group.meeplesP2,
                textStyle = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "): ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            ColoredPairNumbers(
                left = group.p1EndNowScore,
                right = group.p2EndNowScore,
                textStyle = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ColoredPairNumbers(
    left: Int,
    right: Int,
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = left.toString(),
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2B6BE1),
        )
        Text(
            text = "/",
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF212121),
        )
        Text(
            text = right.toString(),
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD53E3E),
        )
    }
}

@Composable
private fun MatchResourceStrip(
    p1Meeples: Int,
    p2Meeples: Int,
    counterLabel: String,
    counterValue: Int,
    priorityTokenPlayer: Int?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeepleCounterDot(
            color = Color(0xFF2B6BE1),
            count = p1Meeples,
        )
        Spacer(modifier = Modifier.width(16.dp))
        MeepleCounterDot(
            color = Color(0xFFD53E3E),
            count = p2Meeples,
        )
        if (priorityTokenPlayer == 1 || priorityTokenPlayer == 2) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "★",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (priorityTokenPlayer == 1) Color(0xFF2B6BE1) else Color(0xFFD53E3E),
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        TileCounterSquare(label = counterLabel, count = counterValue)
    }
}

private fun buildPreviewTiles(match: MatchState, requested: Int, enabled: Boolean): List<String> {
    if (!enabled || requested <= 0) return emptyList()
    if (match.status != MatchStatus.ACTIVE) return emptyList()

    val available = if (match.rules.gameMode == GameMode.RANDOM) {
        (match.rules.randomizedMoveLimit - match.turnState.turnIndex).coerceAtLeast(0)
    } else {
        match.remaining.values.sumOf { it.coerceAtLeast(0) }
    }
    if (available <= 0) return emptyList()

    val out = mutableListOf<String>()
    val nextTurnPlayer = when {
        match.players[2] == null -> 1
        match.turnState.player == 1 -> 2
        else -> 1
    }
    val reserved = match.nextTiles[nextTurnPlayer]
    if (!reserved.isNullOrBlank()) out += reserved
    out += match.drawQueue.filter { it.isNotBlank() }

    val maxCount = minOf(requested, available, out.size)
    return out.take(maxCount)
}

@Composable
private fun ParallelPickStrip(
    round: ParallelRoundState,
    viewerPlayer: Int,
    canAct: Boolean,
    simplifiedView: Boolean,
    tileVisuals: Map<String, TileVisualState>,
    maxHeightPercent: Int,
    onPick: (Int) -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val clampedPercent = maxHeightPercent.coerceIn(6, 35)
    val maxStripHeight = (screenHeight * (clampedPercent / 100f)).coerceAtLeast(34.dp)
    val tileCache = rememberPreviewTileBitmapCache()
    val viewerPick = round.players[viewerPlayer]?.pickIndex
    val opponentPick = round.players.entries.firstOrNull { it.key != viewerPlayer }?.value?.pickIndex
    val canPickNow = canAct && (
        round.phase == ParallelPhase.PICK ||
            (round.phase == ParallelPhase.PLACE && round.players[viewerPlayer]?.tileLocked != true)
        )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        color = Color(0x92FFFFFF),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp, max = maxStripHeight)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            val slots = round.selection.size.coerceAtLeast(1)
            val spacing = 4.dp
            val usableWidth = (maxWidth - spacing * (slots - 1)).coerceAtLeast(20.dp)
            val byWidth = usableWidth / slots
            val byHeight = (maxHeight - 2.dp).coerceAtLeast(20.dp)
            val tileSize = if (byWidth < byHeight) byWidth else byHeight

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                round.selection.forEachIndexed { idx, tileId ->
                    val viewerSelected = viewerPick == idx
                    val opponentSelected = opponentPick == idx
                    val bothSelected = viewerSelected && opponentSelected
                    Box(
                        modifier = Modifier
                            .size(tileSize)
                            .then(
                                if (canPickNow) {
                                    Modifier.clickable { onPick(idx) }
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        UpcomingTileThumb(
                            tileId = tileId,
                            tileSize = tileSize,
                            tileCache = tileCache,
                            simplifiedView = simplifiedView,
                            tileVisual = tileVisuals[tileId],
                        )
                        if (viewerSelected) {
                            DashedPickBorder(
                                color = Color(0xFF2B6BE1),
                                widthDp = 5.2f,
                                insetDp = if (bothSelected) 4f else 3f,
                            )
                        }
                        if (opponentSelected) {
                            DashedPickBorder(
                                color = Color(0xFFD53E3E),
                                widthDp = 5.2f,
                                insetDp = if (bothSelected) 11f else 3f,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashedPickBorder(
    color: Color,
    widthDp: Float,
    insetDp: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize().padding(insetDp.dp)) {
        drawRect(
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = widthDp,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    intervals = floatArrayOf(14f, 8f),
                    phase = 0f,
                ),
            ),
        )
    }
}

@Composable
private fun ParallelConflictControls(
    round: ParallelRoundState,
    viewerPlayer: Int,
    onResolve: (String) -> Unit,
) {
    val conflict = round.conflict ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        color = Color(0xA8FFFFFF),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = conflict.message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            if (conflict.tokenHolder == viewerPlayer) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onResolve("retreat") }) {
                        Text("Retreat")
                    }
                    OutlinedButton(onClick = { onResolve("burn") }) {
                        Text("Burn Token")
                    }
                }
            } else {
                Text(
                    text = "Waiting for priority token holder decision...",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UpcomingTileStrip(
    tileIds: List<String>,
    requestedCount: Int,
    simplifiedView: Boolean,
    tileVisuals: Map<String, TileVisualState>,
    maxHeightPercent: Int,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val clampedPercent = maxHeightPercent.coerceIn(6, 35)
    val maxStripHeight = (screenHeight * (clampedPercent / 100f)).coerceAtLeast(34.dp)
    val tileCache = rememberPreviewTileBitmapCache()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        color = Color(0x92FFFFFF),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp, max = maxStripHeight)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            val slots = requestedCount.coerceAtLeast(1)
            val spacing = 4.dp
            val usableWidth = (maxWidth - spacing * (slots - 1)).coerceAtLeast(20.dp)
            val byWidth = usableWidth / slots
            val byHeight = (maxHeight - 2.dp).coerceAtLeast(20.dp)
            val tileSize = if (byWidth < byHeight) byWidth else byHeight

            if (tileIds.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "No upcoming tiles",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF333333),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tileIds.forEach { tileId ->
                        UpcomingTileThumb(
                            tileId = tileId,
                            tileSize = tileSize,
                            tileCache = tileCache,
                            simplifiedView = simplifiedView,
                            tileVisual = tileVisuals[tileId],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingTileThumb(
    tileId: String,
    tileSize: androidx.compose.ui.unit.Dp,
    tileCache: PreviewTileBitmapCache,
    simplifiedView: Boolean,
    tileVisual: TileVisualState?,
) {
    val bitmap = remember(tileId, simplifiedView) {
        if (simplifiedView) null else tileCache.get(tileId)
    }
    Box(
        modifier = Modifier
            .size(tileSize)
            .border(width = 1.2.dp, color = Color(0xFF4D3216))
            .background(Color(0xFFF3E8CF)),
    ) {
        if (simplifiedView) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSimplifiedTile(
                    sizePx = minOf(size.width, size.height),
                    tile = tileVisual,
                    alpha = 1f,
                )
            }
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Upcoming tile $tileId",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tileId,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2D2012),
                )
            }
        }
    }
}

@Composable
private fun MeepleCounterDot(
    color: Color,
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
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
        )
    }
}

@Composable
private fun TileCounterSquare(label: String, count: Int) {
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
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF2E2E2E),
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
private fun rememberPreviewTileBitmapCache(): PreviewTileBitmapCache {
    val context = LocalContext.current
    return remember(context) { PreviewTileBitmapCache(context) }
}

private class PreviewTileBitmapCache(private val context: Context) {
    private val cache = mutableMapOf<String, ImageBitmap?>()

    fun get(tileId: String): ImageBitmap? {
        return cache.getOrPut(tileId) {
            runCatching {
                context.assets.open("images/tile_${tileId}.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onSave: (String, String, Boolean, String) -> Unit,
    onRefreshLocalIps: () -> Unit,
    onProbeIp: (String) -> Unit,
) {
    var playerName by remember(state.settings.playerName) { mutableStateOf(state.settings.playerName) }
    var port by remember(state.settings.port) { mutableStateOf(state.settings.port.toString()) }
    var simplifiedView by remember(state.settings.simplifiedView) { mutableStateOf(state.settings.simplifiedView) }
    var previewPaneHeightText by remember(state.settings.previewPaneHeightPercent) {
        mutableStateOf(state.settings.previewPaneHeightPercent.toString())
    }
    var probeTargetIp by remember { mutableStateOf("") }

    LaunchedEffect(
        state.settings.playerName,
        state.settings.port,
        state.settings.simplifiedView,
        state.settings.previewPaneHeightPercent,
    ) {
        playerName = state.settings.playerName
        port = state.settings.port.toString()
        simplifiedView = state.settings.simplifiedView
        previewPaneHeightText = state.settings.previewPaneHeightPercent.toString()
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

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = previewPaneHeightText,
            onValueChange = { raw ->
                previewPaneHeightText = raw.filter { it.isDigit() }.take(2)
            },
            label = { Text("Preview pane height (%)") },
            supportingText = { Text("Used when game rules enable preview.") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(playerName, port, simplifiedView, previewPaneHeightText) }) {
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
