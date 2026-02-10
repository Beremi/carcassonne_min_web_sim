package com.carcassonne.lan.network

import com.carcassonne.lan.data.MatchMetadataStore
import com.carcassonne.lan.model.GenericOkResponse
import com.carcassonne.lan.model.HeartbeatRequest
import com.carcassonne.lan.model.InviteRespondRequest
import com.carcassonne.lan.model.JoinRequest
import com.carcassonne.lan.model.PollRequest
import com.carcassonne.lan.model.SubmitTurnRequest
import com.carcassonne.lan.model.TurnIntentRequest
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LanHostServer(
    private val hostGameManager: HostGameManager,
    private val metadataStore: MatchMetadataStore,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Volatile
    private var server: InternalServer? = null

    @Volatile
    var runningPort: Int? = null
        private set

    suspend fun start(port: Int) {
        withContext(Dispatchers.IO) {
            if (runningPort == port && server != null) return@withContext
            stop()
            val created = InternalServer(
                port = port,
                manager = hostGameManager,
                metadataStore = metadataStore,
                ioScope = ioScope,
                json = json,
            )
            created.start(SOCKET_READ_TIMEOUT, false)
            server = created
            runningPort = port
        }
    }

    fun stop() {
        server?.stop()
        server = null
        runningPort = null
    }

    private class InternalServer(
        port: Int,
        private val manager: HostGameManager,
        private val metadataStore: MatchMetadataStore,
        private val ioScope: CoroutineScope,
        private val json: Json,
    ) : NanoHTTPD("0.0.0.0", port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                when (session.method) {
                    Method.GET -> handleGet(session)
                    Method.POST -> handlePost(session)
                    else -> jsonResponse(
                        status = Response.Status.METHOD_NOT_ALLOWED,
                        serializer = GenericOkResponse.serializer(),
                        payload = GenericOkResponse(ok = false, error = "Method not allowed."),
                    )
                }
            } catch (t: Throwable) {
                jsonResponse(
                    status = Response.Status.INTERNAL_ERROR,
                    serializer = GenericOkResponse.serializer(),
                    payload = GenericOkResponse(ok = false, error = t.message ?: "Internal server error."),
                )
            }
        }

        private fun handleGet(session: IHTTPSession): Response {
            return when (session.uri) {
                "/api/ping" -> {
                    jsonResponse(
                        status = Response.Status.OK,
                        serializer = com.carcassonne.lan.model.PingResponse.serializer(),
                        payload = manager.ping(),
                    )
                }

                "/api/health" -> {
                    jsonResponse(
                        status = Response.Status.OK,
                        serializer = GenericOkResponse.serializer(),
                        payload = GenericOkResponse(ok = true),
                    )
                }

                "/api/invite/list" -> {
                    val out = manager.listIncomingInvites()
                    jsonResponse(
                        status = if (out.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = com.carcassonne.lan.model.InviteListResponse.serializer(),
                        payload = out,
                    )
                }

                "/api/invite/status" -> {
                    val inviteId = firstParam(session, "invite_id")
                        ?: return badRequest("Missing invite_id.")
                    val out = manager.inviteStatus(inviteId)
                    jsonResponse(
                        status = if (out.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = com.carcassonne.lan.model.InviteStatusResponse.serializer(),
                        payload = out,
                    )
                }

                else -> jsonResponse(
                    status = Response.Status.NOT_FOUND,
                    serializer = GenericOkResponse.serializer(),
                    payload = GenericOkResponse(ok = false, error = "Not found."),
                )
            }
        }

        private fun handlePost(session: IHTTPSession): Response {
            return when (session.uri) {
                "/api/session/join" -> {
                    val req = decodeBody(session, JoinRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.joinOrReconnect(req.playerName)
                    if (res.ok) {
                        ioScope.launch {
                            res.match?.let { metadataStore.saveHost(it) }
                        }
                        jsonResponse(
                            status = Response.Status.OK,
                            serializer = com.carcassonne.lan.model.JoinResponse.serializer(),
                            payload = res,
                        )
                    } else {
                        jsonResponse(
                            status = Response.Status.BAD_REQUEST,
                            serializer = com.carcassonne.lan.model.JoinResponse.serializer(),
                            payload = res,
                        )
                    }
                }

                "/api/invite/send" -> {
                    val req = decodeBody(session, com.carcassonne.lan.model.InviteSendRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val out = manager.sendInvite(req.fromName)
                    jsonResponse(
                        status = if (out.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = com.carcassonne.lan.model.InviteSendResponse.serializer(),
                        payload = out,
                    )
                }

                "/api/invite/respond" -> {
                    val req = decodeBody(session, InviteRespondRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val out = manager.respondInvite(inviteId = req.inviteId, actionRaw = req.action)
                    jsonResponse(
                        status = if (out.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = com.carcassonne.lan.model.InviteStatusResponse.serializer(),
                        payload = out,
                    )
                }

                "/api/session/reconnect" -> {
                    val req = decodeBody(session, JoinRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.joinOrReconnect(req.playerName)
                    if (res.ok) {
                        ioScope.launch {
                            res.match?.let { metadataStore.saveHost(it) }
                        }
                        jsonResponse(
                            status = Response.Status.OK,
                            serializer = com.carcassonne.lan.model.JoinResponse.serializer(),
                            payload = res,
                        )
                    } else {
                        jsonResponse(
                            status = Response.Status.BAD_REQUEST,
                            serializer = com.carcassonne.lan.model.JoinResponse.serializer(),
                            payload = res,
                        )
                    }
                }

                "/api/session/heartbeat" -> {
                    val req = decodeBody(session, HeartbeatRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.heartbeat(req.token)
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/session/leave" -> {
                    val req = decodeBody(session, HeartbeatRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.removeToken(req.token)
                    if (res.ok) {
                        ioScope.launch {
                            metadataStore.saveHost(manager.snapshot())
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/match/poll" -> {
                    val req = decodeBody(session, PollRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.poll(req.token)
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = com.carcassonne.lan.model.PollResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/match/submit_turn" -> {
                    val req = decodeBody(session, SubmitTurnRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.submitTurn(
                        token = req.token,
                        x = req.x,
                        y = req.y,
                        rotDeg = req.rotDeg,
                        meepleFeatureId = req.meepleFeatureId,
                    )
                    if (res.ok) {
                        ioScope.launch {
                            res.match?.let { metadataStore.saveHost(it) }
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = com.carcassonne.lan.model.SubmitTurnResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/match/intent" -> {
                    val req = decodeBody(session, TurnIntentRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.publishTurnIntent(
                        token = req.token,
                        x = req.x,
                        y = req.y,
                        rotDeg = req.rotDeg,
                        meepleFeatureId = req.meepleFeatureId,
                        locked = req.locked,
                    )
                    if (res.ok) {
                        ioScope.launch {
                            metadataStore.saveHost(manager.snapshot())
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/match/intent/clear" -> {
                    val req = decodeBody(session, HeartbeatRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.clearTurnIntent(req.token)
                    if (res.ok) {
                        ioScope.launch {
                            metadataStore.saveHost(manager.snapshot())
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                else -> jsonResponse(
                    status = Response.Status.NOT_FOUND,
                    serializer = GenericOkResponse.serializer(),
                    payload = GenericOkResponse(ok = false, error = "Not found."),
                )
            }
        }

        private fun <T> decodeBody(session: IHTTPSession, serializer: KSerializer<T>): T? {
            val body = readBody(session) ?: return null
            return try {
                json.decodeFromString(serializer, body)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun readBody(session: IHTTPSession): String? {
            return try {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                files["postData"]
            } catch (_: Exception) {
                null
            }
        }

        private fun badRequest(message: String): Response {
            return jsonResponse(
                status = Response.Status.BAD_REQUEST,
                serializer = GenericOkResponse.serializer(),
                payload = GenericOkResponse(ok = false, error = message),
            )
        }

        private fun firstParam(session: IHTTPSession, key: String): String? {
            return session.parameters[key]?.firstOrNull()?.trim()?.ifBlank { null }
        }

        private fun <T> jsonResponse(status: Response.IStatus, serializer: KSerializer<T>, payload: T): Response {
            val body = json.encodeToString(serializer, payload)
            return newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
    }

    companion object {
        private const val SOCKET_READ_TIMEOUT = 8_000
    }
}
