package com.carcassonne.lan.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carcassonne.lan.core.CarcassonneEngine
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

data class AppUiState(
    val isBootstrapping: Boolean = true,
    val tab: AppTab = AppTab.LOBBY,
    val settings: AppSettings = AppSettings(playerName = "", port = SettingsRepository.FALLBACK_PORT),
    val localIpAddresses: List<String> = emptyList(),
    val hosts: List<HostCard> = emptyList(),
    val session: ClientSession? = null,
    val match: MatchState? = null,
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
    private val metadataStore = MatchMetadataStore(application)
    private val lanClient = LanClient()
    private val lanScanner = LanScanner()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var engine: CarcassonneEngine? = null
    private var hostManager: HostGameManager? = null
    private var hostServer: LanHostServer? = null

    private var pollJob: Job? = null
    private var scanJob: Job? = null

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
                runCatching { lanClient.leave(session) }
            }
            pollJob?.cancel()
            _uiState.update {
                it.copy(
                    session = null,
                    canAct = false,
                    preview = null,
                    lockedPlacement = null,
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
    }

    fun onBoardLongPress(x: Int, y: Int) {
        val e = engine ?: return
        val state = _uiState.value
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
    }

    fun onMeepleOptionTap(featureId: String) {
        val locked = _uiState.value.lockedPlacement ?: return
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
    }

    fun revertLockedPlacement() {
        val state = _uiState.value
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

            persistClientSnapshot(session, res.match)
            val canAct = res.match.status.name == "ACTIVE" && res.match.turnState.player == session.player
            _uiState.update {
                it.copy(
                    match = res.match,
                    canAct = canAct,
                    preview = null,
                    lockedPlacement = null,
                    statusMessage = "Turn submitted.",
                )
            }
        }
    }

    fun saveSettings(playerName: String, portText: String) {
        viewModelScope.launch {
            val parsedPort = portText.toIntOrNull() ?: SettingsRepository.FALLBACK_PORT
            settingsRepository.save(playerName = playerName, port = parsedPort)
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
                    _uiState.update { current ->
                        current.copy(
                            match = poll.match,
                            canAct = canAct,
                            preview = if (canAct) current.preview else null,
                            lockedPlacement = if (canAct) current.lockedPlacement else null,
                            statusMessage = poll.match.lastEvent.ifBlank { current.statusMessage },
                        )
                    }
                    persistClientSnapshot(session, poll.match)
                } else {
                    val cached = metadataStore.loadClient(sessionKey(session))
                    if (cached != null) {
                        _uiState.update {
                            it.copy(
                                match = cached,
                                canAct = false,
                                lockedPlacement = null,
                                statusMessage = "Connection unstable. Showing cached match metadata.",
                            )
                        }
                    }
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

        _uiState.update {
            it.copy(
                session = session,
                match = join.match,
                canAct = join.match.turnState.player == join.player && join.match.status.name == "ACTIVE",
                tab = AppTab.MATCH,
                preview = null,
                lockedPlacement = null,
                outgoingInvite = null,
                statusMessage = "Connected to $address:$port as P${join.player}",
            )
        }

        persistClientSnapshot(session, join.match)
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
        return engine.rotateTile(tileId, rotDeg).features
            .filter { f -> f.type in listOf("road", "city", "field", "cloister") }
            .map { feature ->
                val px = feature.meeplePlacement.getOrNull(0)?.toFloat() ?: 0.5f
                val py = feature.meeplePlacement.getOrNull(1)?.toFloat() ?: 0.5f
                MeepleOptionState(
                    featureId = feature.id,
                    x = px.coerceIn(0.05f, 0.95f),
                    y = py.coerceIn(0.05f, 0.95f),
                    type = feature.type,
                )
            }
            .distinctBy { it.featureId }
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
