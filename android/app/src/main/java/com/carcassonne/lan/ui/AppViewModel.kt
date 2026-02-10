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

enum class AppTab {
    LOBBY,
    MATCH,
    SETTINGS,
}

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
)

data class ScoreGroupState(
    val key: String,
    val type: String,
    val complete: Boolean,
    val points: Int,
    val p1Score: Int,
    val p2Score: Int,
    val tiles: Int,
    val meeplesP1: Int,
    val meeplesP2: Int,
    val tone: String,
    val highlights: List<ScoreHighlightAreaState>,
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
    val selectedScoreGroupKey: String? = null,
    val selectedScoreHighlights: List<ScoreHighlightAreaState> = emptyList(),
    val canAct: Boolean = false,
    val preview: TilePreviewState? = null,
    val lockedPlacement: LockedPlacementState? = null,
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

        if (state.lockedPlacement != null) {
            _uiState.update {
                it.copy(statusMessage = "Placement locked. Choose meeple, Confirm, or Revert.")
            }
            return
        }

        if (!state.canAct) {
            _uiState.update { it.copy(statusMessage = "Wait for your turn.") }
            return
        }

        val tileId = match.turnState.tileId
        if (tileId.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "No tile currently assigned.") }
            return
        }

        val current = state.preview
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
                ),
                statusMessage = if (legal.ok) {
                    "Preview $tileId at ($x,$y), rotation $rot. Long-press to lock tile."
                } else {
                    legal.reason
                },
            )
        }
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

    fun onBoardLongPress(x: Int, y: Int) {
        val e = engine ?: return
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
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
            val selectedKey = _uiState.value.selectedScoreGroupKey
                ?.takeIf { key -> scoreGroups.any { it.key == key } }
            val selectedHighlights = scoreGroups.firstOrNull { it.key == selectedKey }?.highlights.orEmpty()
            val preview = resolveTurnPreview(
                match = res.match,
                canAct = canAct,
                currentPreview = null,
                locked = null,
            )
            _uiState.update {
                it.copy(
                    match = res.match,
                    boardMeeples = boardMeeples,
                    scoreGroups = scoreGroups,
                    selectedScoreGroupKey = selectedKey,
                    selectedScoreHighlights = selectedHighlights,
                    canAct = canAct,
                    preview = preview,
                    lockedPlacement = null,
                    statusMessage = "Turn submitted.",
                )
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
        val localIps = runCatching {
            lanScanner.localIPv4Addresses().toList().sorted()
        }.getOrElse { emptyList() }

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
                isBootstrapping = false,
                settings = settings,
                localIpAddresses = localIps,
                tileVisuals = tileVisuals,
                statusMessage = "Hosting on 0.0.0.0:${settings.port}. Scanning LAN...",
            )
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
            lanScanner.localIPv4Addresses().toList().sorted()
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
                    val selectedKey = currentState.selectedScoreGroupKey
                        ?.takeIf { key -> scoreGroups.any { it.key == key } }
                    val selectedHighlights = scoreGroups
                        .firstOrNull { it.key == selectedKey }
                        ?.highlights
                        .orEmpty()
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
                    )
                    _uiState.update { current ->
                        current.copy(
                            match = poll.match,
                            boardMeeples = boardMeeples,
                            scoreGroups = scoreGroups,
                            selectedScoreGroupKey = selectedKey,
                            selectedScoreHighlights = selectedHighlights,
                            canAct = canAct,
                            preview = nextPreview,
                            lockedPlacement = nextLocked,
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
                                selectedScoreGroupKey = selectedKey,
                                selectedScoreHighlights = selectedHighlights,
                                canAct = false,
                                preview = null,
                                lockedPlacement = null,
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
        val preview = resolveTurnPreview(
            match = join.match,
            canAct = canAct,
            currentPreview = null,
            locked = null,
        )

        _uiState.update {
            it.copy(
                session = session,
                match = join.match,
                boardMeeples = boardMeeples,
                scoreGroups = scoreGroups,
                selectedScoreGroupKey = null,
                selectedScoreHighlights = emptyList(),
                canAct = canAct,
                tab = AppTab.MATCH,
                preview = preview,
                lockedPlacement = null,
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
        val instToCell = match.board.entries.associate { it.value.instId to it.key }
        val groups = mutableListOf<ScoreGroupState>()

        for (group in analysis.groups.values) {
            if (group.type !in listOf("city", "road", "cloister", "field")) continue
            val m1 = group.meeplesByPlayer[1] ?: 0
            val m2 = group.meeplesByPlayer[2] ?: 0
            val tone = when {
                m1 > m2 -> "p1"
                m2 > m1 -> "p2"
                m1 > 0 && m2 > 0 -> "tie"
                else -> "neutral"
            }
            val points = when (group.type) {
                "city" -> e.scoreFeature(group, group.complete)
                "road" -> e.scoreFeature(group, completed = true)
                "cloister" -> e.scoreFeature(group, completed = group.complete)
                "field" -> e.scoreFeature(group, completed = false)
                else -> 0
            }
            val mx = maxOf(m1, m2)
            val p1Score = if (mx > 0 && m1 == mx) points else 0
            val p2Score = if (mx > 0 && m2 == mx) points else 0
            if (p1Score <= 0 && p2Score <= 0) continue

            val highlights = group.nodes.mapNotNull { node ->
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
                )
            }.distinctBy { "${it.cellKey}:${it.fallbackPoint?.x}:${it.fallbackPoint?.y}:${it.polygons.hashCode()}" }

            groups += ScoreGroupState(
                key = group.key,
                type = group.type,
                complete = group.complete,
                points = points,
                p1Score = p1Score,
                p2Score = p2Score,
                tiles = group.tiles.size,
                meeplesP1 = m1,
                meeplesP2 = m2,
                tone = tone,
                highlights = highlights,
            )
        }

        return groups.sortedWith(
            compareBy<ScoreGroupState> { typeRank(it.type) }
                .thenByDescending { it.p1Score + it.p2Score }
                .thenByDescending { it.complete }
                .thenByDescending { it.points }
                .thenBy { it.key },
        )
    }

    private fun featurePolygons(
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
    ): TilePreviewState? {
        val e = engine ?: return null
        if (!canAct) return null
        if (locked != null) return currentPreview

        val tileId = match.turnState.tileId ?: return null
        val current = currentPreview
        if (current != null && current.tileId == tileId) {
            val legal = e.canPlaceAt(match.board, tileId, current.rotDeg, current.x, current.y)
            return current.copy(legal = legal.ok, reason = legal.reason)
        }

        return firstClosestPreview(e, match, tileId)
    }

    private fun firstClosestPreview(
        engine: CarcassonneEngine,
        match: MatchState,
        tileId: String,
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
                    )
                }
            }
        }
        return null
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
    }
}
