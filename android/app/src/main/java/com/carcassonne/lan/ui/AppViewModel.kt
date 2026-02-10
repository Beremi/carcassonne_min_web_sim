package com.carcassonne.lan.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carcassonne.lan.core.CarcassonneEngine
import com.carcassonne.lan.data.AreaPayload
import com.carcassonne.lan.data.AreasRepository
import com.carcassonne.lan.data.AppSettings
import com.carcassonne.lan.data.MatchMetadataStore
import com.carcassonne.lan.data.SettingsRepository
import com.carcassonne.lan.data.TilesetRepository
import com.carcassonne.lan.model.ClientSession
import com.carcassonne.lan.model.MatchState
import com.carcassonne.lan.model.PingResponse
import com.carcassonne.lan.network.HostGameManager
import com.carcassonne.lan.network.LanClient
import com.carcassonne.lan.network.LanHostServer
import com.carcassonne.lan.network.LanScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.ArrayDeque

enum class AppTab {
    LOBBY,
    MATCH,
    SETTINGS,
}

private val SIMPLE_EDGE_ORDER = listOf("N", "E", "S", "W")
private val EDGE_ANCHOR = mapOf(
    "N" to NormPoint(0.5f, 0f),
    "E" to NormPoint(1f, 0.5f),
    "S" to NormPoint(0.5f, 1f),
    "W" to NormPoint(0f, 0.5f),
)
private val EDGE_TO_FIELD_HALVES = mapOf(
    "N" to listOf("Nw", "Ne"),
    "E" to listOf("En", "Es"),
    "S" to listOf("Sw", "Se"),
    "W" to listOf("Wn", "Ws"),
)
private val HALF_FIELD_PORTS = setOf("Nw", "Ne", "En", "Es", "Se", "Sw", "Ws", "Wn")

data class HostCard(
    val address: String,
    val port: Int,
    val ping: PingResponse,
    val isSelf: Boolean,
)

data class MeepleOptionState(
    val featureId: String,
    val x: Float,
    val y: Float,
    val type: String,
)

data class LockedPlacementState(
    val x: Int,
    val y: Int,
    val rotDeg: Int,
    val tileId: String,
    val options: List<MeepleOptionState>,
    val selectedMeepleFeatureId: String? = null,
)

data class IncomingInviteState(
    val inviteId: String,
    val fromName: String,
)

data class OutgoingInviteState(
    val inviteId: String,
    val targetAddress: String,
    val targetPort: Int,
    val targetHostName: String,
)

data class TilePreviewState(
    val x: Int,
    val y: Int,
    val rotDeg: Int,
    val tileId: String,
    val legal: Boolean,
    val reason: String,
    val isUpcomingGhost: Boolean = false,
)

data class PublishedTurnIntentState(
    val token: String,
    val turnIndex: Int,
    val tileId: String,
    val x: Int,
    val y: Int,
    val rotDeg: Int,
    val meepleFeatureId: String? = null,
    val locked: Boolean = false,
)

data class NormPoint(
    val x: Float,
    val y: Float,
)

private data class IntPoint(
    val x: Int,
    val y: Int,
)

data class BoardMeepleState(
    val cellKey: String,
    val x: Float,
    val y: Float,
    val player: Int,
    val isField: Boolean,
)

data class ScoreHighlightAreaState(
    val cellKey: String,
    val tone: String,
    val polygons: List<List<NormPoint>>,
    val fallbackPoint: NormPoint? = null,
    val dashedOnly: Boolean = false,
    val label: String? = null,
)

data class ScoreGroupState(
    val key: String,
    val label: String,
    val type: String,
    val complete: Boolean,
    val closedScored: Boolean,
    val p1CurrentScore: Int,
    val p2CurrentScore: Int,
    val p1EndNowScore: Int,
    val p2EndNowScore: Int,
    val tiles: Int,
    val meeplesP1: Int,
    val meeplesP2: Int,
    val tone: String,
    val highlights: List<ScoreHighlightAreaState>,
)

data class InspectFeatureOptionState(
    val featureId: String,
    val x: Float,
    val y: Float,
    val type: String,
    val tone: String,
)

data class InspectSelectionState(
    val cellKey: String,
    val tileId: String,
    val rotDeg: Int,
    val options: List<InspectFeatureOptionState>,
    val selectedFeatureId: String? = null,
)

data class TileFeatureVisualState(
    val id: String,
    val type: String,
    val ports: List<String>,
    val x: Float,
    val y: Float,
    val pennants: Int,
)

data class TileVisualState(
    val tileId: String,
    val features: List<TileFeatureVisualState>,
)

data class AppUiState(
    val isBootstrapping: Boolean = true,
    val tab: AppTab = AppTab.LOBBY,
    val settings: AppSettings = AppSettings(
        playerName = "",
        port = SettingsRepository.FALLBACK_PORT,
        simplifiedView = false,
    ),
    val localIpAddresses: List<String> = emptyList(),
    val hosts: List<HostCard> = emptyList(),
    val session: ClientSession? = null,
    val match: MatchState? = null,
    val tileVisuals: Map<String, TileVisualState> = emptyMap(),
    val boardMeeples: List<BoardMeepleState> = emptyList(),
    val scoreGroups: List<ScoreGroupState> = emptyList(),
    val projectedFinalScore: Map<Int, Int> = mapOf(1 to 0, 2 to 0),
    val selectedScoreGroupKey: String? = null,
    val selectedScoreHighlights: List<ScoreHighlightAreaState> = emptyList(),
    val canAct: Boolean = false,
    val preview: TilePreviewState? = null,
    val lockedPlacement: LockedPlacementState? = null,
    val inspectSelection: InspectSelectionState? = null,
    val incomingInvites: List<IncomingInviteState> = emptyList(),
    val outgoingInvite: OutgoingInviteState? = null,
    val manualProbeBusy: Boolean = false,
    val manualProbeResult: String = "",
    val statusMessage: String = "Initializing...",
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val tilesetRepository = TilesetRepository(application)
    private val areasRepository = AreasRepository(application)
    private val metadataStore = MatchMetadataStore(application)
    private val lanClient = LanClient()
    private val lanScanner = LanScanner()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var engine: CarcassonneEngine? = null
    private var hostManager: HostGameManager? = null
    private var hostServer: LanHostServer? = null
    private var areaPayload: AreaPayload? = null

    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var lastPublishedIntent: PublishedTurnIntentState? = null
    private val simplifiedFieldContourCache = mutableMapOf<String, Map<String, List<List<NormPoint>>>>()

    init {
        viewModelScope.launch {
            runCatching { bootstrap() }
                .onFailure { err ->
                    Log.e(TAG, "Bootstrap failed", err)
                    _uiState.update {
                        it.copy(
                            isBootstrapping = false,
                            tab = AppTab.SETTINGS,
                            statusMessage = "Startup failed: ${err.message ?: "unknown error"}. Open Settings and retry.",
                        )
                    }
                }
        }
        viewModelScope.launch {
            delay(BOOTSTRAP_GUARD_DELAY_MS)
            if (_uiState.value.isBootstrapping) {
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        tab = AppTab.SETTINGS,
                        statusMessage = "Startup is taking too long. Check port/network in Settings and retry.",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        hostServer?.stop()
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(tab = tab) }
        if (tab == AppTab.SETTINGS) {
            refreshLocalNetworkInfo()
        }
    }

    fun selectScoreGroup(groupKey: String?) {
        _uiState.update { state ->
            val nextKey = if (groupKey == state.selectedScoreGroupKey) null else groupKey
            val highlights = state.scoreGroups.firstOrNull { it.key == nextKey }?.highlights.orEmpty()
            state.copy(
                selectedScoreGroupKey = nextKey,
                selectedScoreHighlights = highlights,
                inspectSelection = state.inspectSelection?.copy(selectedFeatureId = null),
            )
        }
    }

    fun refreshLobbyNow() {
        viewModelScope.launch {
            refreshLocalIps()
            refreshIncomingInvites()
            refreshOutgoingInviteStatus()
            scanOnce()
        }
    }

    fun connectToHost(address: String, port: Int = _uiState.value.settings.port) {
        viewModelScope.launch {
            connectToHostInternal(address, port)
        }
    }

    fun connectToSelfHost() {
        connectToHost("127.0.0.1", _uiState.value.settings.port)
    }

    fun refreshLocalNetworkInfo() {
        viewModelScope.launch {
            refreshLocalIps()
        }
    }

    fun probeSelectedIp(addressInput: String) {
        val address = addressInput.trim()
        if (address.isBlank()) {
            _uiState.update {
                it.copy(
                    manualProbeResult = "Enter an IPv4 address first.",
                    statusMessage = "Enter an IPv4 address first.",
                )
            }
            return
        }
        if (!isLikelyIpv4(address)) {
            _uiState.update {
                it.copy(
                    manualProbeResult = "Invalid IPv4 format: $address",
                    statusMessage = "Invalid IPv4 format: $address",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                manualProbeBusy = true,
                manualProbeResult = "Checking $address...",
                statusMessage = "Checking $address...",
            )
        }

        viewModelScope.launch {
            val port = _uiState.value.settings.port
            val appPing = runCatching { lanClient.ping(address, port) }.getOrNull()
            val ipReachable = runCatching {
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(address).isReachable(900)
                }
            }.getOrDefault(false)

            val msg = when {
                appPing != null -> {
                    "Reachable app at $address:$port (${appPing.hostName}). Same LAN path works."
                }

                ipReachable -> {
                    "IP $address is reachable, but no app replied on port $port."
                }

                else -> {
                    "No response from $address. It may be off or on a different network."
                }
            }

            _uiState.update {
                it.copy(
                    manualProbeBusy = false,
                    manualProbeResult = msg,
                    statusMessage = msg,
                )
            }
        }
    }

    fun disconnectSession() {
        viewModelScope.launch {
            val session = _uiState.value.session
            if (session != null) {
                clearTurnIntentIfNeeded(session)
                runCatching { lanClient.leave(session) }
            }
            pollJob?.cancel()
            lastPublishedIntent = null
            _uiState.update {
                it.copy(
                    session = null,
                    canAct = false,
                    preview = null,
                    lockedPlacement = null,
                    inspectSelection = null,
                    selectedScoreGroupKey = null,
                    selectedScoreHighlights = emptyList(),
                    statusMessage = "Disconnected from match session.",
                )
            }
        }
    }

    fun inviteHost(host: HostCard) {
        val state = _uiState.value
        if (state.outgoingInvite != null) {
            _uiState.update {
                it.copy(statusMessage = "Invite already pending. Wait for response.")
            }
            return
        }

        viewModelScope.launch {
            val send = runCatching {
                lanClient.sendInvite(
                    host = host.address,
                    port = host.port,
                    fromName = _uiState.value.settings.playerName,
                )
            }.getOrNull()

            if (send == null || !send.ok || send.inviteId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(statusMessage = send?.error ?: "Failed to send invite.")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    outgoingInvite = OutgoingInviteState(
                        inviteId = send.inviteId,
                        targetAddress = host.address,
                        targetPort = host.port,
                        targetHostName = host.ping.hostName,
                    ),
                    statusMessage = "Invite sent to ${host.ping.hostName}. Waiting for response...",
                )
            }
        }
    }

    fun acceptInvite(inviteId: String) {
        viewModelScope.launch {
            val out = hostManager?.respondInvite(inviteId = inviteId, actionRaw = "accept")
            if (out == null || !out.ok) {
                _uiState.update {
                    it.copy(statusMessage = out?.error ?: "Failed to accept invite.")
                }
                return@launch
            }
            refreshIncomingInvites()
            _uiState.update { it.copy(statusMessage = "Invite accepted. Opponent can now join.") }
        }
    }

    fun declineInvite(inviteId: String) {
        viewModelScope.launch {
            val out = hostManager?.respondInvite(inviteId = inviteId, actionRaw = "decline")
            if (out == null || !out.ok) {
                _uiState.update {
                    it.copy(statusMessage = out?.error ?: "Failed to decline invite.")
                }
                return@launch
            }
            refreshIncomingInvites()
            _uiState.update { it.copy(statusMessage = "Invite declined.") }
        }
    }

    fun onBoardTap(x: Int, y: Int) {
        val e = engine ?: return
        val state = _uiState.value
        val match = state.match ?: return
        val session = state.session ?: return
        val current = state.preview
        val interactiveUpcomingGhost = !state.canAct && current?.isUpcomingGhost == true

        if (state.lockedPlacement != null) {
            _uiState.update {
                it.copy(statusMessage = "Placement locked. Choose meeple, Confirm, or Revert.")
            }
            return
        }

        if (!state.canAct && !interactiveUpcomingGhost) {
            _uiState.update { it.copy(statusMessage = "Wait for your turn.") }
            return
        }

        val tileId = if (state.canAct) {
            match.turnState.tileId
        } else {
            match.nextTiles[session.player] ?: current?.tileId
        }
        if (tileId.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "No tile currently assigned.") }
            return
        }

        val rot = if (current != null && current.x == x && current.y == y) {
            (current.rotDeg + 90) % 360
        } else {
            0
        }

        val legal = e.canPlaceAt(match.board, tileId, rot, x, y)
        _uiState.update {
            it.copy(
                preview = TilePreviewState(
                    x = x,
                    y = y,
                    rotDeg = rot,
                    tileId = tileId,
                    legal = legal.ok,
                    reason = legal.reason,
                    isUpcomingGhost = !state.canAct,
                ),
                statusMessage = if (legal.ok) {
                    if (state.canAct) {
                        "Preview $tileId at ($x,$y), rotation $rot. Long-press to lock tile."
                    } else {
                        "Upcoming tile preview $tileId at ($x,$y), rotation $rot."
                    }
                } else {
                    legal.reason
                },
                inspectSelection = null,
            )
        }
        if (state.canAct) {
            publishTurnIntent(
                session = session,
                match = match,
                x = x,
                y = y,
                rotDeg = rot,
                meepleFeatureId = null,
                locked = false,
            )
        }
    }

    fun onBoardLongPress(x: Int, y: Int) {
        val e = engine ?: return
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return

        val cellKey = e.keyXY(x, y)
        val occupied = match.board[cellKey]
        if (occupied != null) {
            val options = buildInspectOptions(match = match, cellKey = cellKey)
            if (options.isEmpty()) {
                _uiState.update { it.copy(statusMessage = "No area features on this tile.") }
                return
            }
            _uiState.update {
                it.copy(
                    inspectSelection = InspectSelectionState(
                        cellKey = cellKey,
                        tileId = occupied.tileId,
                        rotDeg = occupied.rotDeg,
                        options = options,
                        selectedFeatureId = null,
                    ),
                    lockedPlacement = null,
                    statusMessage = "Area inspect mode: tap a marker to highlight connected area.",
                )
            }
            return
        }

        if (!state.canAct) return
        val preview = state.preview ?: return
        if (preview.x != x || preview.y != y) return
        if (!preview.legal) {
            _uiState.update { it.copy(statusMessage = preview.reason) }
            return
        }

        val options = buildMeepleOptions(e, preview.tileId, preview.rotDeg)
        _uiState.update {
            it.copy(
                lockedPlacement = LockedPlacementState(
                    x = preview.x,
                    y = preview.y,
                    rotDeg = preview.rotDeg,
                    tileId = preview.tileId,
                    options = options,
                    selectedMeepleFeatureId = null,
                ),
                inspectSelection = null,
                statusMessage = "Tile locked. Tap marker for meeple, then Confirm or Revert.",
            )
        }
        publishTurnIntent(
            session = session,
            match = match,
            x = preview.x,
            y = preview.y,
            rotDeg = preview.rotDeg,
            meepleFeatureId = null,
            locked = true,
        )
    }

    fun onMeepleOptionTap(featureId: String) {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        val locked = state.lockedPlacement ?: return
        val next = if (locked.selectedMeepleFeatureId == featureId) null else featureId
        _uiState.update {
            it.copy(
                lockedPlacement = locked.copy(selectedMeepleFeatureId = next),
                statusMessage = if (next == null) {
                    "Meeple cleared. Confirm to place tile without meeple."
                } else {
                    "Meeple selected: $featureId. Confirm to submit turn."
                },
            )
        }
        publishTurnIntent(
            session = session,
            match = match,
            x = locked.x,
            y = locked.y,
            rotDeg = locked.rotDeg,
            meepleFeatureId = next,
            locked = true,
        )
    }

    fun revertLockedPlacement() {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        val locked = state.lockedPlacement ?: return
        _uiState.update {
            it.copy(
                lockedPlacement = null,
                preview = TilePreviewState(
                    x = locked.x,
                    y = locked.y,
                    rotDeg = locked.rotDeg,
                    tileId = locked.tileId,
                    legal = true,
                    reason = "OK",
                ),
                inspectSelection = null,
                statusMessage = "Placement reverted. You can rotate or choose another cell.",
            )
        }
        publishTurnIntent(
            session = session,
            match = match,
            x = locked.x,
            y = locked.y,
            rotDeg = locked.rotDeg,
            meepleFeatureId = null,
            locked = false,
        )
    }

    fun confirmLockedPlacement() {
        val state = _uiState.value
        val session = state.session ?: return
        val locked = state.lockedPlacement ?: return

        viewModelScope.launch {
            val res = runCatching {
                lanClient.submitTurn(
                    session = session,
                    x = locked.x,
                    y = locked.y,
                    rotDeg = locked.rotDeg,
                    meepleFeatureId = locked.selectedMeepleFeatureId,
                )
            }.getOrNull()

            if (res == null || !res.ok || res.match == null) {
                _uiState.update {
                    it.copy(statusMessage = res?.error ?: "Failed to submit turn.")
                }
                return@launch
            }

            lastPublishedIntent = null
            persistClientSnapshot(session, res.match)
            val canAct = res.match.status.name == "ACTIVE" && res.match.turnState.player == session.player
            val boardMeeples = buildBoardMeeples(res.match)
            val scoreGroups = buildScoreGroups(res.match)
            val projectedFinalScore = buildProjectedFinalScore(res.match)
            val selectedKey = _uiState.value.selectedScoreGroupKey
                ?.takeIf { key -> scoreGroups.any { it.key == key } }
            val selectedHighlights = scoreGroups.firstOrNull { it.key == selectedKey }?.highlights.orEmpty()
            val preview = resolveTurnPreview(
                match = res.match,
                canAct = canAct,
                currentPreview = null,
                locked = null,
                viewerPlayer = session.player,
            )
            _uiState.update {
                it.copy(
                    match = res.match,
                    boardMeeples = boardMeeples,
                    scoreGroups = scoreGroups,
                    projectedFinalScore = projectedFinalScore,
                    selectedScoreGroupKey = selectedKey,
                    selectedScoreHighlights = selectedHighlights,
                    canAct = canAct,
                    preview = preview,
                    lockedPlacement = null,
                    inspectSelection = null,
                    statusMessage = "Turn submitted.",
                )
            }
        }
    }

    fun onInspectOptionTap(featureId: String) {
        val state = _uiState.value
        val match = state.match ?: return
        val inspect = state.inspectSelection ?: return
        val selected = buildFeatureSelection(
            match = match,
            cellKey = inspect.cellKey,
            featureId = featureId,
        ) ?: return

        _uiState.update {
            it.copy(
                inspectSelection = inspect.copy(selectedFeatureId = featureId),
                selectedScoreGroupKey = selected.groupKey,
                selectedScoreHighlights = selected.highlights,
                statusMessage = if (selected.tone == "free") {
                    "Highlighted unclaimed area."
                } else {
                    "Highlighted ${selected.type} area."
                },
            )
        }
    }

    fun clearInspectSelection() {
        _uiState.update {
            if (it.inspectSelection == null) {
                it
            } else {
                it.copy(inspectSelection = null)
            }
        }
    }

    fun saveSettings(playerName: String, portText: String, simplifiedView: Boolean) {
        viewModelScope.launch {
            val parsedPort = portText.toIntOrNull() ?: SettingsRepository.FALLBACK_PORT
            settingsRepository.save(
                playerName = playerName,
                port = parsedPort,
                simplifiedView = simplifiedView,
            )
            val fresh = settingsRepository.settings.first()

            hostManager?.configureHostPlayer(fresh.playerName)
            hostServer?.start(fresh.port)
            hostManager?.snapshot()?.let { metadataStore.saveHost(it) }

            _uiState.update {
                it.copy(
                    settings = fresh,
                    statusMessage = "Settings saved. Hosting on port ${fresh.port}.",
                )
            }

            refreshLocalIps()
            scanOnce()
            refreshIncomingInvites()

            val session = _uiState.value.session
            if (session != null && session.hostAddress == "127.0.0.1" && session.port != fresh.port) {
                disconnectSession()
            }
        }
    }

    private suspend fun bootstrap() {
        settingsRepository.initializeDefaultsIfNeeded()
        val settings = settingsRepository.settings.first()
        val tileset = tilesetRepository.loadTileset()
        areaPayload = runCatching { areasRepository.loadAreas() }.getOrNull()
        val tileVisuals = tileset.tiles.associate { tile ->
            tile.id to TileVisualState(
                tileId = tile.id,
                features = tile.features.map { feature ->
                    TileFeatureVisualState(
                        id = feature.id,
                        type = feature.type,
                        ports = feature.ports,
                        x = (feature.meeplePlacement.getOrNull(0)?.toFloat() ?: 0.5f).coerceIn(0f, 1f),
                        y = (feature.meeplePlacement.getOrNull(1)?.toFloat() ?: 0.5f).coerceIn(0f, 1f),
                        pennants = runCatching {
                            feature.tags["pennants"]?.toString()?.filter { c -> c.isDigit() }?.toInt()
                        }.getOrNull() ?: 0,
                    )
                },
            )
        }

        _uiState.update {
            it.copy(
                isBootstrapping = false,
                settings = settings,
                localIpAddresses = emptyList(),
                tileVisuals = tileVisuals,
                statusMessage = "Starting LAN host on 0.0.0.0:${settings.port}...",
            )
        }
        simplifiedFieldContourCache.clear()

        engine = CarcassonneEngine(tileset)
        val manager = HostGameManager(engine = engine ?: error("Engine unavailable"))
        manager.restoreMatchIfCompatible(metadataStore.loadHost())
        manager.configureHostPlayer(settings.playerName)
        hostManager = manager

        val server = LanHostServer(
            hostGameManager = manager,
            metadataStore = metadataStore,
        )
        server.start(settings.port)
        hostServer = server

        manager.snapshot().let { metadataStore.saveHost(it) }

        _uiState.update {
            it.copy(
                statusMessage = "Hosting on 0.0.0.0:${settings.port}. Scanning LAN...",
            )
        }

        viewModelScope.launch {
            refreshLocalIps()
        }
        refreshIncomingInvites()
        startScanningLoop()
        scanOnce()
    }

    private fun startScanningLoop() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            while (isActive) {
                refreshIncomingInvites()
                refreshOutgoingInviteStatus()
                scanOnce()
                delay(6500)
            }
        }
    }

    private suspend fun scanOnce() {
        val state = _uiState.value
        val hosts = runCatching {
            lanScanner.scan(state.settings.port).map {
                HostCard(
                    address = it.address,
                    port = it.port,
                    ping = it.ping,
                    isSelf = it.isSelf,
                )
            }
        }.getOrElse { emptyList() }
            .filterNot { host ->
                host.isSelf || host.ping.hostName.equals(state.settings.playerName, ignoreCase = true)
            }
            .distinctBy { host -> host.ping.hostName.trim().lowercase() }

        _uiState.update {
            it.copy(
                hosts = hosts,
                statusMessage = if (hosts.isEmpty()) {
                    "No hosts discovered on port ${state.settings.port}."
                } else {
                    "Discovered ${hosts.size} host(s) on port ${state.settings.port}."
                },
            )
        }
    }

    private suspend fun refreshLocalIps() {
        val ips = runCatching {
            withContext(Dispatchers.IO) {
                lanScanner.localIPv4Addresses().toList().sorted()
            }
        }.getOrElse { emptyList() }
        _uiState.update { it.copy(localIpAddresses = ips) }
    }

    private fun startPolling(session: ClientSession) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var heartbeatCounter = 0
            while (isActive) {
                val activeSession = _uiState.value.session
                if (activeSession == null || activeSession.token != session.token) {
                    break
                }

                val poll = runCatching { lanClient.poll(session) }.getOrNull()
                if (poll != null && poll.ok && poll.match != null) {
                    val canAct = poll.canAct
                    val currentState = _uiState.value
                    val boardMeeples = buildBoardMeeples(poll.match)
                    val scoreGroups = buildScoreGroups(poll.match)
                    val projectedFinalScore = buildProjectedFinalScore(poll.match)
                    val selectedKey = currentState.selectedScoreGroupKey
                        ?.takeIf { key -> scoreGroups.any { it.key == key } }
                    val nextLocked = currentState.lockedPlacement?.takeIf {
                        canAct &&
                            it.tileId == poll.match.turnState.tileId &&
                            poll.match.turnState.player == session.player
                    }
                    val nextPreview = resolveTurnPreview(
                        match = poll.match,
                        canAct = canAct,
                        currentPreview = currentState.preview,
                        locked = nextLocked,
                        viewerPlayer = session.player,
                    )
                    val nextInspect = currentState.inspectSelection?.let { inspect ->
                        val inst = poll.match.board[inspect.cellKey]
                        if (inst == null || inst.tileId != inspect.tileId || inst.rotDeg != inspect.rotDeg) {
                            null
                        } else {
                            val refreshedOptions = buildInspectOptions(
                                match = poll.match,
                                cellKey = inspect.cellKey,
                            )
                            val selectedFeature = inspect.selectedFeatureId
                                ?.takeIf { selected -> refreshedOptions.any { it.featureId == selected } }
                            inspect.copy(
                                options = refreshedOptions,
                                selectedFeatureId = selectedFeature,
                            )
                        }
                    }
                    val inspectSelection = nextInspect?.selectedFeatureId?.let { selectedFeature ->
                        buildFeatureSelection(
                            match = poll.match,
                            cellKey = nextInspect.cellKey,
                            featureId = selectedFeature,
                        )
                    }
                    val nextSelectedKey = inspectSelection?.groupKey ?: selectedKey
                    val nextHighlights = inspectSelection?.highlights
                        ?: scoreGroups.firstOrNull { it.key == nextSelectedKey }?.highlights.orEmpty()
                    _uiState.update { current ->
                        current.copy(
                            match = poll.match,
                            boardMeeples = boardMeeples,
                            scoreGroups = scoreGroups,
                            projectedFinalScore = projectedFinalScore,
                            selectedScoreGroupKey = nextSelectedKey,
                            selectedScoreHighlights = nextHighlights,
                            canAct = canAct,
                            preview = nextPreview,
                            lockedPlacement = nextLocked,
                            inspectSelection = nextInspect,
                            statusMessage = poll.match.lastEvent.ifBlank { current.statusMessage },
                        )
                    }
                    persistClientSnapshot(session, poll.match)
                    maybePublishCurrentTurnIntent(_uiState.value)
                } else {
                    val cached = metadataStore.loadClient(sessionKey(session))
                    if (cached != null) {
                        val boardMeeples = buildBoardMeeples(cached)
                        val scoreGroups = buildScoreGroups(cached)
                        val projectedFinalScore = buildProjectedFinalScore(cached)
                        val selectedKey = _uiState.value.selectedScoreGroupKey
                            ?.takeIf { key -> scoreGroups.any { it.key == key } }
                        val selectedHighlights = scoreGroups
                            .firstOrNull { it.key == selectedKey }
                            ?.highlights
                            .orEmpty()
                        _uiState.update {
                            it.copy(
                                match = cached,
                                boardMeeples = boardMeeples,
                                scoreGroups = scoreGroups,
                                projectedFinalScore = projectedFinalScore,
                                selectedScoreGroupKey = selectedKey,
                                selectedScoreHighlights = selectedHighlights,
                                canAct = false,
                                preview = null,
                                lockedPlacement = null,
                                inspectSelection = null,
                                statusMessage = "Connection unstable. Showing cached match metadata.",
                            )
                        }
                    }
                    clearTurnIntentIfNeeded(session)
                }

                heartbeatCounter += 1
                if (heartbeatCounter % 8 == 0) {
                    runCatching { lanClient.heartbeat(session) }
                }

                delay(1200)
            }
        }
    }

    private fun persistClientSnapshot(session: ClientSession, match: MatchState) {
        viewModelScope.launch {
            metadataStore.saveClient(sessionKey(session), match)
        }
    }

    private fun sessionKey(session: ClientSession): String {
        return "${session.hostAddress}_${session.port}_${session.playerName}"
    }

    private suspend fun connectToHostInternal(address: String, port: Int) {
        val settings = _uiState.value.settings
        val join = runCatching {
            lanClient.join(host = address, port = port, playerName = settings.playerName)
        }.getOrNull()

        if (join == null || !join.ok || join.token.isNullOrBlank() || join.player == null || join.match == null) {
            _uiState.update {
                it.copy(statusMessage = join?.error ?: "Could not connect to $address:$port")
            }
            return
        }

        val session = ClientSession(
            hostAddress = address,
            port = port,
            token = join.token,
            player = join.player,
            playerName = settings.playerName,
        )
        lastPublishedIntent = null

        val canAct = join.match.turnState.player == join.player && join.match.status.name == "ACTIVE"
        val boardMeeples = buildBoardMeeples(join.match)
        val scoreGroups = buildScoreGroups(join.match)
        val projectedFinalScore = buildProjectedFinalScore(join.match)
        val preview = resolveTurnPreview(
            match = join.match,
            canAct = canAct,
            currentPreview = null,
            locked = null,
            viewerPlayer = join.player,
        )

        _uiState.update {
            it.copy(
                session = session,
                match = join.match,
                boardMeeples = boardMeeples,
                scoreGroups = scoreGroups,
                projectedFinalScore = projectedFinalScore,
                selectedScoreGroupKey = null,
                selectedScoreHighlights = emptyList(),
                canAct = canAct,
                tab = AppTab.MATCH,
                preview = preview,
                lockedPlacement = null,
                inspectSelection = null,
                outgoingInvite = null,
                statusMessage = "Connected to $address:$port as P${join.player}",
            )
        }

        persistClientSnapshot(session, join.match)
        maybePublishCurrentTurnIntent(_uiState.value)
        startPolling(session)
    }

    private suspend fun refreshIncomingInvites() {
        val list = hostManager?.listIncomingInvites()?.invites.orEmpty()
        _uiState.update {
            it.copy(
                incomingInvites = list.map { item ->
                    IncomingInviteState(
                        inviteId = item.id,
                        fromName = item.fromName,
                    )
                }
            )
        }
    }

    private suspend fun refreshOutgoingInviteStatus() {
        val outgoing = _uiState.value.outgoingInvite ?: return
        val status = runCatching {
            lanClient.inviteStatus(
                host = outgoing.targetAddress,
                port = outgoing.targetPort,
                inviteId = outgoing.inviteId,
            )
        }.getOrNull() ?: return

        if (!status.ok) {
            _uiState.update {
                it.copy(
                    outgoingInvite = null,
                    statusMessage = status.error ?: "Invite status unavailable.",
                )
            }
            return
        }

        when (status.status?.lowercase()) {
            "accepted" -> {
                _uiState.update {
                    it.copy(
                        outgoingInvite = null,
                        statusMessage = "Invite accepted by ${outgoing.targetHostName}. Connecting...",
                    )
                }
                connectToHostInternal(outgoing.targetAddress, outgoing.targetPort)
            }

            "declined", "expired", "used" -> {
                _uiState.update {
                    it.copy(
                        outgoingInvite = null,
                        statusMessage = "Invite ${status.status}.",
                    )
                }
            }
        }
    }

    private fun buildMeepleOptions(
        engine: CarcassonneEngine,
        tileId: String,
        rotDeg: Int,
    ): List<MeepleOptionState> {
        return engine.baseFeatures(tileId)
            .filter { f -> f.type in listOf("road", "city", "field", "cloister") }
            .map { feature ->
                val placement = engine.featurePlacementNormalized(
                    tileId = tileId,
                    featureLocalId = feature.id,
                    rotDeg = rotDeg,
                ) ?: Pair(0.5f, 0.5f)
                MeepleOptionState(
                    featureId = feature.id,
                    x = placement.first.coerceIn(0.05f, 0.95f),
                    y = placement.second.coerceIn(0.05f, 0.95f),
                    type = feature.type,
                )
            }
            .distinctBy { it.featureId }
    }

    private data class FeatureSelection(
        val groupKey: String?,
        val type: String,
        val tone: String,
        val highlights: List<ScoreHighlightAreaState>,
    )

    private fun buildInspectOptions(
        match: MatchState,
        cellKey: String,
    ): List<InspectFeatureOptionState> {
        val e = engine ?: return emptyList()
        val inst = match.board[cellKey] ?: return emptyList()
        val analysis = e.analyzeBoard(match.board)
        return e.baseFeatures(inst.tileId)
            .filter { f -> f.type in listOf("road", "city", "field", "cloister") }
            .mapNotNull { feature ->
                val point = e.featurePlacementNormalized(
                    tileId = inst.tileId,
                    featureLocalId = feature.id,
                    rotDeg = inst.rotDeg,
                ) ?: return@mapNotNull null
                val nodeKey = "${inst.instId}:${feature.id}"
                val group = if (analysis.nodeMeta.containsKey(nodeKey)) {
                    analysis.groups[analysis.uf.find(nodeKey)]
                } else {
                    null
                }
                val m1 = group?.meeplesByPlayer?.get(1) ?: 0
                val m2 = group?.meeplesByPlayer?.get(2) ?: 0
                InspectFeatureOptionState(
                    featureId = feature.id,
                    x = point.first.coerceIn(0.05f, 0.95f),
                    y = point.second.coerceIn(0.05f, 0.95f),
                    type = feature.type,
                    tone = toneFromMeeples(m1, m2),
                )
            }
            .distinctBy { it.featureId }
    }

    private fun buildFeatureSelection(
        match: MatchState,
        cellKey: String,
        featureId: String,
    ): FeatureSelection? {
        val e = engine ?: return null
        val inst = match.board[cellKey] ?: return null
        val analysis = e.analyzeBoard(match.board)
        val nodeKey = "${inst.instId}:$featureId"
        if (!analysis.nodeMeta.containsKey(nodeKey)) return null
        val group = analysis.groups[analysis.uf.find(nodeKey)] ?: return null
        val m1 = group.meeplesByPlayer[1] ?: 0
        val m2 = group.meeplesByPlayer[2] ?: 0
        val tone = toneFromMeeples(m1, m2)
        val highlights = buildHighlightsFromGroupNodes(
            match = match,
            nodes = group.nodes,
            tone = tone,
        ).toMutableList()
        if (group.type == "field") {
            val groupsByKey = analysis.groups.values.associateBy { it.key }
            appendFieldClosedCityOverlays(
                match = match,
                fieldGroup = group,
                groupsByKey = groupsByKey,
                output = highlights,
            )
        }
        return FeatureSelection(
            groupKey = group.key,
            type = group.type,
            tone = tone,
            highlights = highlights,
        )
    }

    private fun buildBoardMeeples(match: MatchState): List<BoardMeepleState> {
        val e = engine ?: return emptyList()
        return match.board.entries.flatMap { (cellKey, inst) ->
            inst.meeples.mapNotNull { meeple ->
                val point = e.featurePlacementNormalized(
                    tileId = inst.tileId,
                    featureLocalId = meeple.featureLocalId,
                    rotDeg = inst.rotDeg,
                ) ?: return@mapNotNull null
                val type = e.featureType(inst.tileId, meeple.featureLocalId).orEmpty()
                BoardMeepleState(
                    cellKey = cellKey,
                    x = point.first.coerceIn(0.0f, 1.0f),
                    y = point.second.coerceIn(0.0f, 1.0f),
                    player = meeple.player,
                    isField = type == "field",
                )
            }
        }
    }

    private fun buildScoreGroups(match: MatchState): List<ScoreGroupState> {
        val e = engine ?: return emptyList()
        val analysis = e.analyzeBoard(match.board)
        val groupsByKey = analysis.groups.values.associateBy { it.key }
        val openGroups = mutableListOf<ScoreGroupState>()
        val closedGroups = mutableListOf<ScoreGroupState>()

        for (group in analysis.groups.values) {
            if (group.type !in listOf("city", "road", "cloister", "field")) continue
            if (group.key in match.scoredKeys) continue
            val m1 = group.meeplesByPlayer[1] ?: 0
            val m2 = group.meeplesByPlayer[2] ?: 0
            val hasMeeples = (m1 + m2) > 0
            val winners = e.winnersOfGroup(group)
            if (winners.isEmpty() && !hasMeeples) continue
            val tone = toneFromMeeples(m1, m2)

            val endNowValue = e.scoreEndNowValue(group)
            if (endNowValue <= 0 && !(group.type == "field" && hasMeeples)) continue
            val p1CurrentScore = 0
            val p2CurrentScore = 0
            val p1EndNowScore = if (1 in winners) endNowValue else 0
            val p2EndNowScore = if (2 in winners) endNowValue else 0
            val highlights = buildHighlightsFromGroupNodes(
                match = match,
                nodes = group.nodes,
                tone = tone,
            ).toMutableList()
            if (group.type == "field") {
                appendFieldClosedCityOverlays(
                    match = match,
                    fieldGroup = group,
                    groupsByKey = groupsByKey,
                    output = highlights,
                )
            }

            openGroups += ScoreGroupState(
                key = group.key,
                label = "",
                type = group.type,
                complete = group.complete,
                closedScored = false,
                p1CurrentScore = p1CurrentScore,
                p2CurrentScore = p2CurrentScore,
                p1EndNowScore = p1EndNowScore,
                p2EndNowScore = p2EndNowScore,
                tiles = group.tiles.size,
                meeplesP1 = m1,
                meeplesP2 = m2,
                tone = tone,
                highlights = highlights,
            )
        }

        for (entry in match.scoredAreaHistory.values) {
            if (entry.p1 <= 0 && entry.p2 <= 0) continue
            val group = groupsByKey[entry.key]
            val tone = toneFromMeeples(entry.p1, entry.p2)
            val highlights = if (group != null) {
                buildHighlightsFromGroupNodes(
                    match = match,
                    nodes = group.nodes,
                    tone = tone,
                )
            } else {
                emptyList()
            }
            closedGroups += ScoreGroupState(
                key = entry.key,
                label = "",
                type = entry.type,
                complete = true,
                closedScored = true,
                p1CurrentScore = entry.p1,
                p2CurrentScore = entry.p2,
                p1EndNowScore = entry.p1,
                p2EndNowScore = entry.p2,
                tiles = group?.tiles?.size ?: 0,
                meeplesP1 = 0,
                meeplesP2 = 0,
                tone = tone,
                highlights = highlights,
            )
        }

        val sortedOpen = openGroups.sortedWith(
            compareBy<ScoreGroupState> { typeRank(it.type) }
                .thenByDescending { it.p1EndNowScore + it.p2EndNowScore }
                .thenBy { it.key },
        )
        val sortedClosed = closedGroups.sortedWith(
            compareBy<ScoreGroupState> { typeRank(it.type) }
                .thenByDescending { it.p1CurrentScore + it.p2CurrentScore }
                .thenBy { it.key },
        )

        val counters = mutableMapOf<String, Int>()
        return (sortedOpen + sortedClosed).map { group ->
            val idx = (counters[group.type] ?: 0) + 1
            counters[group.type] = idx
            group.copy(label = "${group.type.uppercase()}$idx")
        }
    }

    private fun appendFieldClosedCityOverlays(
        match: MatchState,
        fieldGroup: CarcassonneEngine.FeatureGroup,
        groupsByKey: Map<String, CarcassonneEngine.FeatureGroup>,
        output: MutableList<ScoreHighlightAreaState>,
    ) {
        if (fieldGroup.type != "field" || fieldGroup.adjCompletedCities.isEmpty()) return
        var idx = 1
        for (cityKey in fieldGroup.adjCompletedCities.sorted()) {
            val cityGroup = groupsByKey[cityKey] ?: continue
            val dashed = buildHighlightsFromGroupNodes(
                match = match,
                nodes = cityGroup.nodes,
                tone = "neutral",
                dashedOnly = true,
            )
            output += dashed
            val marker = buildClosedCityMarker(
                match = match,
                cityGroup = cityGroup,
                number = idx.toString(),
            )
            if (marker != null) output += marker
            idx++
        }
    }

    private fun buildClosedCityMarker(
        match: MatchState,
        cityGroup: CarcassonneEngine.FeatureGroup,
        number: String,
    ): ScoreHighlightAreaState? {
        val e = engine ?: return null
        if (cityGroup.nodes.isEmpty()) return null
        val instToCell = match.board.entries.associate { it.value.instId to it.key }

        val parsed = cityGroup.nodes.mapNotNull { node ->
            val instId = node.substringBefore(":").toIntOrNull() ?: return@mapNotNull null
            val featureId = node.substringAfter(":", "")
            if (featureId.isBlank()) return@mapNotNull null
            val cellKey = instToCell[instId] ?: return@mapNotNull null
            Triple(instId, featureId, cellKey)
        }
        if (parsed.isEmpty()) return null

        val cells = parsed.mapNotNull { triple ->
            val (x, y) = e.parseXY(triple.third)
            Triple(triple, x.toFloat(), y.toFloat())
        }
        if (cells.isEmpty()) return null
        val avgX = cells.sumOf { it.second.toDouble() } / cells.size.toDouble()
        val avgY = cells.sumOf { it.third.toDouble() } / cells.size.toDouble()
        val best = cells.minByOrNull { (_, x, y) ->
            val dx = x - avgX.toFloat()
            val dy = y - avgY.toFloat()
            dx * dx + dy * dy
        } ?: return null

        val (node, _, _) = best
        val (_, featureId, cellKey) = node
        val inst = match.board[cellKey] ?: return null
        val point = e.featurePlacementNormalized(
            tileId = inst.tileId,
            featureLocalId = featureId,
            rotDeg = inst.rotDeg,
        ) ?: Pair(0.5f, 0.5f)

        return ScoreHighlightAreaState(
            cellKey = cellKey,
            tone = "neutral",
            polygons = emptyList(),
            fallbackPoint = NormPoint(point.first, point.second),
            dashedOnly = true,
            label = number,
        )
    }

    private fun buildProjectedFinalScore(match: MatchState): Map<Int, Int> {
        val e = engine ?: return match.score
        val analysis = e.analyzeBoard(match.board)
        val projected = mutableMapOf(
            1 to (match.score[1] ?: 0),
            2 to (match.score[2] ?: 0),
        )
        for (group in analysis.groups.values) {
            if (group.type !in listOf("city", "road", "cloister", "field")) continue
            if (group.type != "field" && group.key in match.scoredKeys) continue
            val winners = e.winnersOfGroup(group)
            if (winners.isEmpty()) continue
            val pts = e.scoreEndNowValue(group)
            if (pts <= 0) continue
            for (winner in winners) {
                projected[winner] = (projected[winner] ?: 0) + pts
            }
        }
        return projected
    }

    private fun buildHighlightsFromGroupNodes(
        match: MatchState,
        nodes: Set<String>,
        tone: String,
        dashedOnly: Boolean = false,
    ): List<ScoreHighlightAreaState> {
        val e = engine ?: return emptyList()
        val instToCell = match.board.entries.associate { it.value.instId to it.key }
        return nodes.mapNotNull { node ->
            val instId = node.substringBefore(":").toIntOrNull() ?: return@mapNotNull null
            val featureId = node.substringAfter(":", "")
            if (featureId.isBlank()) return@mapNotNull null
            val cellKey = instToCell[instId] ?: return@mapNotNull null
            val inst = match.board[cellKey] ?: return@mapNotNull null

            val point = e.featurePlacementNormalized(
                tileId = inst.tileId,
                featureLocalId = featureId,
                rotDeg = inst.rotDeg,
            )

            val polygons = featurePolygons(
                tileId = inst.tileId,
                featureId = featureId,
                rotDeg = inst.rotDeg,
            )

            ScoreHighlightAreaState(
                cellKey = cellKey,
                tone = tone,
                polygons = polygons,
                fallbackPoint = point?.let { NormPoint(it.first, it.second) },
                dashedOnly = dashedOnly,
            )
        }.distinctBy {
            "${it.cellKey}:${it.fallbackPoint?.x}:${it.fallbackPoint?.y}:${it.polygons.hashCode()}:${it.dashedOnly}:${it.label}"
        }
    }

    private fun featurePolygons(
        tileId: String,
        featureId: String,
        rotDeg: Int,
    ): List<List<NormPoint>> {
        val featureType = _uiState.value.tileVisuals[tileId]
            ?.features
            ?.firstOrNull { it.id == featureId }
            ?.type

        if (_uiState.value.settings.simplifiedView) {
            if (featureType == "field") {
                val fromAreas = areaFeaturePolygons(
                    tileId = tileId,
                    featureId = featureId,
                    rotDeg = rotDeg,
                )
                if (fromAreas.isNotEmpty()) return fromAreas
            }
            val simplified = simplifiedFeaturePolygons(
                tileId = tileId,
                featureId = featureId,
                rotDeg = rotDeg,
            )
            if (simplified.isNotEmpty()) return simplified
        }
        return areaFeaturePolygons(
            tileId = tileId,
            featureId = featureId,
            rotDeg = rotDeg,
        )
    }

    private fun areaFeaturePolygons(
        tileId: String,
        featureId: String,
        rotDeg: Int,
    ): List<List<NormPoint>> {
        val tile = areaPayload?.tiles?.get(tileId) ?: return emptyList()
        val feature = tile.features[featureId] ?: return emptyList()
        return feature.polygons.mapNotNull polygonMap@{ poly ->
            if (poly.size < 3) return@polygonMap null
            val rotated = poly.mapNotNull pointMap@{ pt ->
                val x = pt.getOrNull(0)?.toFloat() ?: return@pointMap null
                val y = pt.getOrNull(1)?.toFloat() ?: return@pointMap null
                rotateNormPoint(x, y, rotDeg)
            }
            if (rotated.size < 3) null else rotated
        }
    }

    private fun simplifiedFeaturePolygons(
        tileId: String,
        featureId: String,
        rotDeg: Int,
    ): List<List<NormPoint>> {
        val tile = _uiState.value.tileVisuals[tileId] ?: return emptyList()
        val allFeatures = tile.features.filter { it.type in listOf("road", "city", "field", "cloister") }
        val feature = allFeatures.firstOrNull { it.id == featureId } ?: return emptyList()

        val localPolygons = when (feature.type) {
            "city" -> {
                val city = simplifiedCityPoints(feature.ports)
                if (city.size >= 3) listOf(city) else emptyList()
            }

            "road" -> simplifiedRoadPolygons(feature, allFeatures)
            "field" -> simplifiedFieldPolygons(tileId, feature, allFeatures)
            "cloister" -> listOf(circlePolygon(feature.x, feature.y, 0.115f, 18))
            else -> emptyList()
        }

        return localPolygons
            .map { poly -> poly.map { p -> rotateNormPoint(p.x, p.y, rotDeg) } }
            .filter { it.size >= 3 }
    }

    private fun simplifiedCityPoints(ports: List<String>): List<NormPoint> {
        val p = ports
            .filter { it in SIMPLE_EDGE_ORDER }
            .distinct()
            .sortedBy { SIMPLE_EDGE_ORDER.indexOf(it) }
        if (p.isEmpty()) return listOf(
            NormPoint(0.34f, 0.34f),
            NormPoint(0.66f, 0.34f),
            NormPoint(0.66f, 0.66f),
            NormPoint(0.34f, 0.66f),
        )
        if (p.size == 4) return listOf(
            NormPoint(0f, 0f),
            NormPoint(1f, 0f),
            NormPoint(1f, 1f),
            NormPoint(0f, 1f),
        )
        if (p.size == 1) return cityOneEdgeFanPoints(p[0])
        if (p.size == 2) {
            val a = p[0]
            val b = p[1]
            val opposite = (a == "N" && b == "S") ||
                (a == "S" && b == "N") ||
                (a == "E" && b == "W") ||
                (a == "W" && b == "E")
            if (opposite) {
                val base = listOf(
                    NormPoint(0f, 0f),
                    NormPoint(1f, 0f),
                    NormPoint(0.82f, 0.46f),
                    NormPoint(0.82f, 0.54f),
                    NormPoint(1f, 1f),
                    NormPoint(0f, 1f),
                    NormPoint(0.18f, 0.54f),
                    NormPoint(0.18f, 0.46f),
                )
                val steps = if (a == "N" || a == "S") 0 else 1
                return rotatePointsCW(base, steps)
            }
            return cityAdjacentEdgesPoints(p)
        }
        val missing = SIMPLE_EDGE_ORDER.firstOrNull { it !in p.toSet() } ?: "S"
        return cityThreeEdgesPoints(missing)
    }

    private fun simplifiedRoadPolygons(
        road: TileFeatureVisualState,
        allFeatures: List<TileFeatureVisualState>,
    ): List<List<NormPoint>> {
        val ports = edgePortsOfFeature(road)
        if (ports.isEmpty()) return emptyList()
        val roads = allFeatures.filter { it.type == "road" }
        val onePortRoads = roads.filter { edgePortsOfFeature(it).size == 1 }
        val multiPortRoads = roads.filter { edgePortsOfFeature(it).size > 1 }
        val sharedJunction = if (multiPortRoads.isEmpty() && onePortRoads.size >= 3 && ports.size == 1) {
            NormPoint(0.5f, 0.5f)
        } else {
            null
        }

        if (ports.size == 1) {
            val anchor = EDGE_ANCHOR[ports[0]] ?: NormPoint(0.5f, 0f)
            val target = sharedJunction ?: roadDeadEndTarget(road, allFeatures)
            return buildRoadTube(
                points = listOf(anchor, target),
                width = 0.14f,
                includeJointCaps = true,
            )
        }

        if (ports.size == 2) {
            val a = EDGE_ANCHOR[ports[0]] ?: NormPoint(0.5f, 0f)
            val b = EDGE_ANCHOR[ports[1]] ?: NormPoint(0.5f, 1f)
            val c1 = edgeInwardControl(ports[0], 0.24f)
            val c2 = edgeInwardControl(ports[1], 0.24f)
            val pathPoints = sampleCubicPath(a, c1, c2, b, 20)
            return buildRoadTube(
                points = pathPoints,
                width = 0.12f,
                includeJointCaps = false,
            )
        }

        val anchors = ports.mapNotNull { EDGE_ANCHOR[it] }
        if (anchors.isEmpty()) return emptyList()
        val junction = NormPoint(
            x = ((anchors.sumOf { it.x.toDouble() } + road.x) / (anchors.size + 1).toDouble()).toFloat(),
            y = ((anchors.sumOf { it.y.toDouble() } + road.y) / (anchors.size + 1).toDouble()).toFloat(),
        )
        val polys = mutableListOf<List<NormPoint>>()
        for (anchor in anchors) {
            polys += buildRoadTube(
                points = listOf(anchor, junction),
                width = 0.12f,
                includeJointCaps = false,
            )
        }
        polys += circlePolygon(junction.x, junction.y, 0.055f, 18)
        return polys
    }

    private fun simplifiedFieldPolygons(
        tileId: String,
        field: TileFeatureVisualState,
        allFeatures: List<TileFeatureVisualState>,
    ): List<List<NormPoint>> {
        val byField = simplifiedFieldContourCache.getOrPut(tileId) {
            buildSimplifiedFieldContours(tileId, allFeatures)
        }
        return byField[field.id].orEmpty()
    }

    private fun buildSimplifiedFieldContours(
        tileId: String,
        allFeatures: List<TileFeatureVisualState>,
    ): Map<String, List<List<NormPoint>>> {
        val gridSize = 84
        val blocked = BooleanArray(gridSize * gridSize)
        val fields = allFeatures.filter { it.type == "field" }

        fun mark(polys: List<List<NormPoint>>) {
            for (poly in polys) {
                rasterizePolygon(poly, gridSize, blocked)
            }
        }

        for (feature in allFeatures) {
            when (feature.type) {
                "city" -> mark(listOf(simplifiedCityPoints(feature.ports)))
                "road" -> mark(simplifiedRoadPolygons(feature, allFeatures))
                "cloister" -> mark(listOf(circlePolygon(feature.x, feature.y, 0.115f, 22)))
            }
        }

        val comp = IntArray(gridSize * gridSize) { -1 }
        val componentCells = mutableMapOf<Int, MutableList<Int>>()
        var nextCid = 0

        for (idx in 0 until blocked.size) {
            if (blocked[idx] || comp[idx] >= 0) continue
            val cid = nextCid++
            val queue = ArrayDeque<Int>()
            queue.addLast(idx)
            comp[idx] = cid
            val cells = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                cells += cur
                val x = cur % gridSize
                val y = cur / gridSize
                val nbrs = listOf(
                    Pair(x - 1, y),
                    Pair(x + 1, y),
                    Pair(x, y - 1),
                    Pair(x, y + 1),
                )
                for ((nx, ny) in nbrs) {
                    if (nx !in 0 until gridSize || ny !in 0 until gridSize) continue
                    val ni = ny * gridSize + nx
                    if (blocked[ni] || comp[ni] >= 0) continue
                    comp[ni] = cid
                    queue.addLast(ni)
                }
            }
            componentCells[cid] = cells
        }

        if (componentCells.isEmpty()) return fields.associate { field ->
            field.id to listOf(circlePolygon(field.x, field.y, 0.14f, 16))
        }

        val loopCache = mutableMapOf<Int, List<List<NormPoint>>>()
        val out = linkedMapOf<String, List<List<NormPoint>>>()
        for (field in fields) {
            val sx = (field.x * (gridSize - 1)).toInt().coerceIn(0, gridSize - 1)
            val sy = (field.y * (gridSize - 1)).toInt().coerceIn(0, gridSize - 1)
            val cid = findNearestComponentId(sx, sy, gridSize, comp)
            if (cid < 0) {
                out[field.id] = listOf(circlePolygon(field.x, field.y, 0.14f, 16))
                continue
            }
            val loops = loopCache.getOrPut(cid) { componentBoundaryLoops(comp, cid, gridSize) }
            if (loops.isEmpty()) {
                out[field.id] = listOf(circlePolygon(field.x, field.y, 0.14f, 16))
                continue
            }
            val largest = loops.maxByOrNull { kotlin.math.abs(polygonSignedArea(it)) }
            if (largest == null || largest.size < 3) {
                out[field.id] = listOf(circlePolygon(field.x, field.y, 0.14f, 16))
            } else {
                out[field.id] = listOf(smoothClosedPolygon(largest, 2))
            }
        }
        return out
    }

    private fun rasterizePolygon(poly: List<NormPoint>, gridSize: Int, blocked: BooleanArray) {
        if (poly.size < 3) return
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (pt in poly) {
            minX = kotlin.math.min(minX, pt.x)
            maxX = kotlin.math.max(maxX, pt.x)
            minY = kotlin.math.min(minY, pt.y)
            maxY = kotlin.math.max(maxY, pt.y)
        }
        val gx0 = ((minX * gridSize).toInt() - 1).coerceIn(0, gridSize - 1)
        val gx1 = ((maxX * gridSize).toInt() + 1).coerceIn(0, gridSize - 1)
        val gy0 = ((minY * gridSize).toInt() - 1).coerceIn(0, gridSize - 1)
        val gy1 = ((maxY * gridSize).toInt() + 1).coerceIn(0, gridSize - 1)

        for (gy in gy0..gy1) {
            for (gx in gx0..gx1) {
                val px = (gx + 0.5f) / gridSize.toFloat()
                val py = (gy + 0.5f) / gridSize.toFloat()
                if (pointInPolygon(px, py, poly)) {
                    blocked[gy * gridSize + gx] = true
                }
            }
        }
    }

    private fun pointInPolygon(x: Float, y: Float, poly: List<NormPoint>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val xi = poly[i].x
            val yi = poly[i].y
            val xj = poly[j].x
            val yj = poly[j].y
            val yiAbove = yi > y
            val yjAbove = yj > y
            if (yiAbove != yjAbove) {
                val denom = (yj - yi).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }
                val crossX = (xj - xi) * (y - yi) / denom + xi
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun findNearestComponentId(
        sx: Int,
        sy: Int,
        gridSize: Int,
        comp: IntArray,
    ): Int {
        val base = comp[sy * gridSize + sx]
        if (base >= 0) return base

        var bestCid = -1
        var bestDist = Int.MAX_VALUE
        for (idx in comp.indices) {
            val cid = comp[idx]
            if (cid < 0) continue
            val x = idx % gridSize
            val y = idx / gridSize
            val dx = x - sx
            val dy = y - sy
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestCid = cid
            }
        }
        return bestCid
    }

    private fun componentBoundaryLoops(
        comp: IntArray,
        cid: Int,
        gridSize: Int,
    ): List<List<NormPoint>> {
        val edges = linkedMapOf<IntPoint, MutableList<IntPoint>>()

        fun addEdge(a: IntPoint, b: IntPoint) {
            val list = edges.getOrPut(a) { mutableListOf() }
            list += b
        }

        for (idx in comp.indices) {
            if (comp[idx] != cid) continue
            val x = idx % gridSize
            val y = idx / gridSize
            if (y == 0 || comp[(y - 1) * gridSize + x] != cid) addEdge(IntPoint(x, y), IntPoint(x + 1, y))
            if (x == gridSize - 1 || comp[y * gridSize + (x + 1)] != cid) addEdge(IntPoint(x + 1, y), IntPoint(x + 1, y + 1))
            if (y == gridSize - 1 || comp[(y + 1) * gridSize + x] != cid) addEdge(IntPoint(x + 1, y + 1), IntPoint(x, y + 1))
            if (x == 0 || comp[y * gridSize + (x - 1)] != cid) addEdge(IntPoint(x, y + 1), IntPoint(x, y))
        }

        val loops = mutableListOf<List<NormPoint>>()
        while (true) {
            val startEntry = edges.entries.firstOrNull { it.value.isNotEmpty() } ?: break
            val start = startEntry.key
            val loop = mutableListOf<IntPoint>()
            var current = start
            var guard = 0
            while (guard < (gridSize * gridSize * 6)) {
                guard++
                loop += current
                val nextList = edges[current] ?: break
                if (nextList.isEmpty()) {
                    edges.remove(current)
                    break
                }
                val next = nextList.removeAt(nextList.lastIndex)
                if (nextList.isEmpty()) edges.remove(current)
                current = next
                if (current == start) break
            }
            if (loop.size < 3) continue
            val asNorm = removeCollinear(loop.map { pt ->
                NormPoint(
                    x = (pt.x.toFloat() / gridSize.toFloat()).coerceIn(0f, 1f),
                    y = (pt.y.toFloat() / gridSize.toFloat()).coerceIn(0f, 1f),
                )
            })
            if (asNorm.size >= 3) loops += asNorm
        }
        return loops
    }

    private fun removeCollinear(poly: List<NormPoint>): List<NormPoint> {
        if (poly.size < 4) return poly
        val out = mutableListOf<NormPoint>()
        for (i in poly.indices) {
            val a = poly[(i - 1 + poly.size) % poly.size]
            val b = poly[i]
            val c = poly[(i + 1) % poly.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (kotlin.math.abs(cross) > 1e-5f) {
                out += b
            }
        }
        return if (out.size >= 3) out else poly
    }

    private fun smoothClosedPolygon(poly: List<NormPoint>, iterations: Int): List<NormPoint> {
        var out = poly
        repeat(iterations.coerceAtLeast(0)) {
            if (out.size < 3) return out
            val smoothed = mutableListOf<NormPoint>()
            for (i in out.indices) {
                val p0 = out[i]
                val p1 = out[(i + 1) % out.size]
                smoothed += NormPoint(
                    x = (0.75f * p0.x + 0.25f * p1.x).coerceIn(0f, 1f),
                    y = (0.75f * p0.y + 0.25f * p1.y).coerceIn(0f, 1f),
                )
                smoothed += NormPoint(
                    x = (0.25f * p0.x + 0.75f * p1.x).coerceIn(0f, 1f),
                    y = (0.25f * p0.y + 0.75f * p1.y).coerceIn(0f, 1f),
                )
            }
            out = smoothed
        }
        return out
    }

    private fun polygonSignedArea(poly: List<NormPoint>): Float {
        if (poly.size < 3) return 0f
        var acc = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            acc += (p.x * q.y) - (q.x * p.y)
        }
        return 0.5f * acc
    }

    private fun roadDeadEndTarget(
        road: TileFeatureVisualState,
        allFeatures: List<TileFeatureVisualState>,
    ): NormPoint {
        val port = edgePortsOfFeature(road).firstOrNull() ?: return NormPoint(0.5f, 0.5f)
        val split = roadSplitsFieldsAtEdge(road, allFeatures, port)
        if (allFeatures.any { it.type == "cloister" }) return NormPoint(0.5f, 0.5f)
        if (allFeatures.any { it.type == "city" }) {
            return when (port) {
                "N" -> NormPoint(0.5f, 0.52f)
                "S" -> NormPoint(0.5f, 0.48f)
                "E" -> NormPoint(0.48f, 0.5f)
                "W" -> NormPoint(0.52f, 0.5f)
                else -> NormPoint(0.5f, 0.5f)
            }
        }
        return when (port) {
            "N" -> NormPoint(0.5f, if (split) 0.36f else 0.28f)
            "S" -> NormPoint(0.5f, if (split) 0.64f else 0.72f)
            "E" -> NormPoint(if (split) 0.64f else 0.72f, 0.5f)
            "W" -> NormPoint(if (split) 0.36f else 0.28f, 0.5f)
            else -> NormPoint(0.5f, 0.5f)
        }
    }

    private fun roadSplitsFieldsAtEdge(
        road: TileFeatureVisualState,
        allFeatures: List<TileFeatureVisualState>,
        edge: String,
    ): Boolean {
        if (road.type != "road") return false
        val fieldOwner = mutableMapOf<String, String>()
        for (feature in allFeatures) {
            if (feature.type != "field") continue
            for (port in feature.ports) {
                if (port in HALF_FIELD_PORTS) {
                    fieldOwner[port] = feature.id
                }
            }
        }
        val halves = EDGE_TO_FIELD_HALVES[edge] ?: return false
        val a = fieldOwner[halves[0]]
        val b = fieldOwner[halves[1]]
        return a != null && b != null && a != b
    }

    private fun edgePortsOfFeature(feature: TileFeatureVisualState): List<String> {
        return feature.ports
            .filter { it in SIMPLE_EDGE_ORDER }
            .distinct()
            .sortedBy { SIMPLE_EDGE_ORDER.indexOf(it) }
    }

    private fun edgeInwardControl(edge: String, distance: Float): NormPoint {
        return when (edge) {
            "N" -> NormPoint(0.5f, distance)
            "S" -> NormPoint(0.5f, 1f - distance)
            "E" -> NormPoint(1f - distance, 0.5f)
            else -> NormPoint(distance, 0.5f)
        }
    }

    private fun buildRoadTube(
        points: List<NormPoint>,
        width: Float,
        includeJointCaps: Boolean,
    ): List<List<NormPoint>> {
        if (points.size < 2) return emptyList()
        val polys = mutableListOf<List<NormPoint>>()
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len <= 0.0001f) continue
            val nx = -dy / len
            val ny = dx / len
            val hw = width / 2f
            polys += listOf(
                NormPoint((a.x + nx * hw).coerceIn(0f, 1f), (a.y + ny * hw).coerceIn(0f, 1f)),
                NormPoint((a.x - nx * hw).coerceIn(0f, 1f), (a.y - ny * hw).coerceIn(0f, 1f)),
                NormPoint((b.x - nx * hw).coerceIn(0f, 1f), (b.y - ny * hw).coerceIn(0f, 1f)),
                NormPoint((b.x + nx * hw).coerceIn(0f, 1f), (b.y + ny * hw).coerceIn(0f, 1f)),
            )
        }
        if (includeJointCaps) {
            for (pt in points) {
                polys += circlePolygon(pt.x, pt.y, width * 0.28f, 14)
            }
        }
        return polys
    }

    private fun sampleCubicPath(
        p0: NormPoint,
        c1: NormPoint,
        c2: NormPoint,
        p1: NormPoint,
        samples: Int,
    ): List<NormPoint> {
        if (samples <= 1) return listOf(p0, p1)
        val out = mutableListOf<NormPoint>()
        for (i in 0..samples) {
            val t = i.toFloat() / samples.toFloat()
            val mt = 1f - t
            out += NormPoint(
                x = mt * mt * mt * p0.x +
                    3f * mt * mt * t * c1.x +
                    3f * mt * t * t * c2.x +
                    t * t * t * p1.x,
                y = mt * mt * mt * p0.y +
                    3f * mt * mt * t * c1.y +
                    3f * mt * t * t * c2.y +
                    t * t * t * p1.y,
            )
        }
        return out
    }

    private fun circlePolygon(cx: Float, cy: Float, radius: Float, steps: Int): List<NormPoint> {
        val n = steps.coerceAtLeast(8)
        return (0 until n).map { i ->
            val angle = (kotlin.math.PI * 2.0 * i.toDouble()) / n.toDouble()
            NormPoint(
                x = (cx + kotlin.math.cos(angle).toFloat() * radius).coerceIn(0f, 1f),
                y = (cy + kotlin.math.sin(angle).toFloat() * radius).coerceIn(0f, 1f),
            )
        }
    }

    private fun cityOneEdgeFanPoints(edge: String, depth: Float = 0.30f, samples: Int = 16): List<NormPoint> {
        val base = mutableListOf(
            NormPoint(0f, 0f),
            NormPoint(1f, 0f),
        )
        for (i in 1 until samples) {
            val x = 1f - (i.toFloat() / samples.toFloat())
            val t = kotlin.math.sin(kotlin.math.PI * x.toDouble()).toFloat()
            val y = depth * maxOf(0f, t)
            base += NormPoint(x, y)
        }
        val steps = when (edge) {
            "N" -> 0
            "E" -> 1
            "S" -> 2
            "W" -> 3
            else -> 0
        }
        return rotatePointsCW(base, steps)
    }

    private fun cityAdjacentEdgesPoints(ports: List<String>): List<NormPoint> {
        val s = ports.toSet()
        val base = mutableListOf(
            NormPoint(0f, 0f),
            NormPoint(1f, 0f),
        )
        base += sampleQuadraticPoints(
            p0 = NormPoint(1f, 0f),
            c = NormPoint(0.28f, 0.28f),
            p1 = NormPoint(0f, 1f),
            n = 16,
        )
        base += NormPoint(0f, 1f)
        val steps = when {
            s.contains("N") && s.contains("E") -> 1
            s.contains("E") && s.contains("S") -> 2
            s.contains("S") && s.contains("W") -> 3
            else -> 0
        }
        return rotatePointsCW(base, steps)
    }

    private fun cityThreeEdgesPoints(missing: String): List<NormPoint> {
        val base = mutableListOf(
            NormPoint(0f, 0f),
            NormPoint(1f, 0f),
            NormPoint(1f, 1f),
        )
        base += sampleQuadraticPoints(
            p0 = NormPoint(1f, 1f),
            c = NormPoint(0.5f, 0.72f),
            p1 = NormPoint(0f, 1f),
            n = 18,
        )
        base += NormPoint(0f, 1f)
        val steps = when (missing) {
            "S" -> 0
            "W" -> 1
            "N" -> 2
            "E" -> 3
            else -> 0
        }
        return rotatePointsCW(base, steps)
    }

    private fun sampleQuadraticPoints(
        p0: NormPoint,
        c: NormPoint,
        p1: NormPoint,
        n: Int,
    ): List<NormPoint> {
        if (n <= 1) return emptyList()
        val out = mutableListOf<NormPoint>()
        for (i in 1 until n) {
            val t = i.toFloat() / n.toFloat()
            val mt = 1f - t
            out += NormPoint(
                x = mt * mt * p0.x + 2f * mt * t * c.x + t * t * p1.x,
                y = mt * mt * p0.y + 2f * mt * t * c.y + t * t * p1.y,
            )
        }
        return out
    }

    private fun rotatePointsCW(points: List<NormPoint>, steps: Int): List<NormPoint> {
        return points.map { rotatePointCW(it, steps) }
    }

    private fun rotatePointCW(point: NormPoint, steps: Int): NormPoint {
        var x = point.x
        var y = point.y
        repeat(((steps % 4) + 4) % 4) {
            val nx = 1f - y
            val ny = x
            x = nx
            y = ny
        }
        return NormPoint(x, y)
    }

    private fun convexHull(points: List<NormPoint>): List<NormPoint> {
        if (points.size <= 3) return points.distinctBy { "${it.x},${it.y}" }
        val sorted = points
            .distinctBy { "${it.x},${it.y}" }
            .sortedWith(compareBy<NormPoint> { it.x }.thenBy { it.y })

        fun cross(o: NormPoint, a: NormPoint, b: NormPoint): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        val lower = mutableListOf<NormPoint>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower += p
        }
        val upper = mutableListOf<NormPoint>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper += p
        }
        if (upper.isNotEmpty()) upper.removeAt(upper.lastIndex)
        if (lower.isNotEmpty()) lower.removeAt(lower.lastIndex)
        val hull = lower + upper
        return if (hull.size >= 3) hull else sorted.take(3)
    }

    private fun rotateNormPoint(x: Float, y: Float, rotDeg: Int): NormPoint {
        val rot = ((rotDeg % 360) + 360) % 360
        return when (rot) {
            90 -> NormPoint(1f - y, x)
            180 -> NormPoint(1f - x, 1f - y)
            270 -> NormPoint(y, 1f - x)
            else -> NormPoint(x, y)
        }
    }

    private fun resolveTurnPreview(
        match: MatchState,
        canAct: Boolean,
        currentPreview: TilePreviewState?,
        locked: LockedPlacementState?,
        viewerPlayer: Int?,
    ): TilePreviewState? {
        val e = engine ?: return null
        if (!canAct) {
            val upcomingTile = viewerPlayer?.let { player -> match.nextTiles[player] }
            if (!upcomingTile.isNullOrBlank()) {
                if (
                    currentPreview != null &&
                    currentPreview.isUpcomingGhost &&
                    currentPreview.tileId == upcomingTile
                ) {
                    val legal = e.canPlaceAt(
                        board = match.board,
                        tileId = upcomingTile,
                        rotDeg = currentPreview.rotDeg,
                        x = currentPreview.x,
                        y = currentPreview.y,
                    )
                    return currentPreview.copy(
                        legal = legal.ok,
                        reason = legal.reason,
                        isUpcomingGhost = true,
                    )
                }
                return firstClosestPreview(
                    engine = e,
                    match = match,
                    tileId = upcomingTile,
                    asUpcomingGhost = true,
                )
            }
            return null
        }
        if (locked != null) return currentPreview

        val tileId = match.turnState.tileId ?: return null
        val current = currentPreview
        if (current != null && current.tileId == tileId && !current.isUpcomingGhost) {
            val legal = e.canPlaceAt(match.board, tileId, current.rotDeg, current.x, current.y)
            return current.copy(legal = legal.ok, reason = legal.reason, isUpcomingGhost = false)
        }

        return firstClosestPreview(e, match, tileId, asUpcomingGhost = false)
    }

    private fun firstClosestPreview(
        engine: CarcassonneEngine,
        match: MatchState,
        tileId: String,
        asUpcomingGhost: Boolean = false,
    ): TilePreviewState? {
        val candidates = engine.buildFrontier(match.board)
            .sortedWith(
                compareBy<Pair<Int, Int>> { kotlin.math.abs(it.first) + kotlin.math.abs(it.second) }
                    .thenBy { kotlin.math.abs(it.second) }
                    .thenBy { kotlin.math.abs(it.first) }
                    .thenBy { it.second }
                    .thenBy { it.first },
            )

        for ((x, y) in candidates) {
            for (rot in listOf(0, 90, 180, 270)) {
                val legal = engine.canPlaceAt(match.board, tileId, rot, x, y)
                if (legal.ok) {
                    return TilePreviewState(
                        x = x,
                        y = y,
                        rotDeg = rot,
                        tileId = tileId,
                        legal = true,
                        reason = legal.reason,
                        isUpcomingGhost = asUpcomingGhost,
                    )
                }
            }
        }
        return null
    }

    private fun toneFromMeeples(m1: Int, m2: Int): String {
        return when {
            m1 <= 0 && m2 <= 0 -> "free"
            m1 > m2 -> "p1"
            m2 > m1 -> "p2"
            m1 > 0 && m2 > 0 -> "tie"
            else -> "free"
        }
    }

    private fun maybePublishCurrentTurnIntent(state: AppUiState) {
        val session = state.session ?: return
        val match = state.match ?: return
        if (!state.canAct || match.status.name != "ACTIVE" || match.turnState.player != session.player) {
            clearTurnIntentIfNeeded(session)
            return
        }

        val locked = state.lockedPlacement
        if (locked != null) {
            publishTurnIntent(
                session = session,
                match = match,
                x = locked.x,
                y = locked.y,
                rotDeg = locked.rotDeg,
                meepleFeatureId = locked.selectedMeepleFeatureId,
                locked = true,
            )
            return
        }

        val preview = state.preview
        if (preview != null) {
            publishTurnIntent(
                session = session,
                match = match,
                x = preview.x,
                y = preview.y,
                rotDeg = preview.rotDeg,
                meepleFeatureId = null,
                locked = false,
            )
            return
        }

        clearTurnIntentIfNeeded(session)
    }

    private fun publishTurnIntent(
        session: ClientSession,
        match: MatchState,
        x: Int,
        y: Int,
        rotDeg: Int,
        meepleFeatureId: String?,
        locked: Boolean,
    ) {
        val tileId = match.turnState.tileId ?: return
        if (match.status.name != "ACTIVE" || match.turnState.player != session.player) return
        val normalizedRot = (((rotDeg % 360) + 360) % 360 / 90) * 90
        val normalizedMeeple = meepleFeatureId?.trim().orEmpty().ifBlank { null }
        val fingerprint = PublishedTurnIntentState(
            token = session.token,
            turnIndex = match.turnState.turnIndex,
            tileId = tileId,
            x = x,
            y = y,
            rotDeg = normalizedRot,
            meepleFeatureId = normalizedMeeple,
            locked = locked,
        )
        if (lastPublishedIntent == fingerprint) return
        lastPublishedIntent = fingerprint

        viewModelScope.launch {
            val ok = runCatching {
                lanClient.publishTurnIntent(
                    session = session,
                    x = x,
                    y = y,
                    rotDeg = normalizedRot,
                    meepleFeatureId = normalizedMeeple,
                    locked = locked,
                )
            }.getOrDefault(false)
            if (!ok && lastPublishedIntent == fingerprint) {
                lastPublishedIntent = null
            }
        }
    }

    private fun clearTurnIntentIfNeeded(session: ClientSession) {
        val published = lastPublishedIntent ?: return
        lastPublishedIntent = null
        if (published.token != session.token) return
        viewModelScope.launch {
            runCatching { lanClient.clearTurnIntent(session) }
        }
    }

    private fun typeRank(type: String): Int {
        return when (type.lowercase()) {
            "city" -> 0
            "road" -> 1
            "cloister" -> 2
            "field" -> 3
            else -> 99
        }
    }

    private fun isLikelyIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isBlank()) return false
            val n = part.toIntOrNull() ?: return false
            if (n !in 0..255) return false
        }
        return true
    }

    companion object {
        private const val TAG = "AppViewModel"
        private const val BOOTSTRAP_GUARD_DELAY_MS = 10_000L
    }
}
