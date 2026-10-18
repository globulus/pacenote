package com.theglobulus.claudecar.net

import com.theglobulus.claudecar.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class PostResult { OK, UNAUTHORIZED, REJECTED, NETWORK_ERROR }

class BridgeClient(private val settings: Settings) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun request(path: String): Request.Builder = Request.Builder()
        .url(settings.bridgeUrl + path)
        .header("Authorization", "Bearer ${settings.token}")

    suspend fun postMessage(id: String, sessionId: String, text: String): PostResult =
        post("/message", JSONObject().put("id", id).put("session_id", sessionId).put("text", text))

    /** Start a new session in a folder; its conversation opens when it's ready. */
    suspend fun createSession(cwd: String): PostResult =
        post("/sessions", JSONObject().put("id", UUID.randomUUID().toString()).put("cwd", cwd))

    suspend fun sessions(): Result<SessionList> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request("/sessions").get().build()).execute().use { res ->
                if (!res.isSuccessful) error(if (res.code == 401) "Wrong token" else "Mac error ${res.code}")
                SessionListParser.parse(res.body.string())
            }
        }.recoverCatching { throw IllegalStateException(describe(it)) }
    }

    /** A one-line, human-readable result of GET /health. */
    suspend fun health(): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request("/health").get().build()).execute().use { res ->
                when {
                    res.code == 401 -> "Wrong token (401)"
                    !res.isSuccessful -> "Mac error ${res.code}"
                    else -> {
                        val o = JSONObject(res.body.string())
                        val blocked = o.has("documents_access") && !o.isNull("documents_access") && !o.getBoolean("documents_access")
                        "Mac reachable · ${o.optInt("listeners")} phone(s) connected · ${o.optInt("running")} turn(s) running" +
                            if (blocked) " · macOS blocks ~/Documents: allow bun in Privacy & Security" else ""
                    }
                }
            }
        } catch (e: Exception) {
            describe(e)
        }
    }

    private suspend fun post(path: String, body: JSONObject): PostResult = withContext(Dispatchers.IO) {
        try {
            val req = request(path).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { res ->
                when {
                    res.isSuccessful -> PostResult.OK
                    res.code == 401 -> PostResult.UNAUTHORIZED
                    res.code in 400..499 -> PostResult.REJECTED
                    else -> PostResult.NETWORK_ERROR
                }
            }
        } catch (e: Exception) {
            PostResult.NETWORK_ERROR
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        // Nothing listening: the service isn't running on the Mac.
        is java.net.ConnectException ->
            "The Mac refused the connection: is the claude-car service running? (scripts/service.sh status)"
        is java.net.SocketTimeoutException -> "Can't reach the Mac. Is Tailscale on (here and on the Mac), and is the Mac awake?"
        is IllegalStateException -> e.message ?: "Error"
        else -> "Can't reach the Mac: ${e.message ?: e.javaClass.simpleName}"
    }
}
