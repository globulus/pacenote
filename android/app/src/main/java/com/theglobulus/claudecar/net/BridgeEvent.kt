package com.theglobulus.claudecar.net

import org.json.JSONObject

/** What the Mac service sends on GET /events (see bridge/src/http.ts). */
sealed interface BridgeEvent {
    data class Message(
        val id: String,
        /** The Claude Code session, or "hub" for the Claude Car command chat. */
        val sessionId: String,
        val role: String, // "user" | "claude" | "system"
        val text: String,
        val ts: Long,
        val speak: Boolean,
        val clientId: String? = null,
        /** "open" asks the phone to show this session's conversation in the car. */
        val kind: String? = null,
        val title: String? = null,
    ) : BridgeEvent

    /** The client can't just continue: it is new, the service restarted, or it fell behind. */
    data class Reset(val boot: String, val gap: Boolean) : BridgeEvent
}

object BridgeEventParser {
    fun parse(type: String?, data: String): BridgeEvent? = try {
        val o = JSONObject(data)
        when (type) {
            "reset" -> BridgeEvent.Reset(boot = o.getString("boot"), gap = o.optBoolean("gap"))
            "message", null -> BridgeEvent.Message(
                id = o.getString("id"),
                sessionId = o.getString("session_id"),
                role = o.getString("role"),
                text = o.getString("text"),
                ts = o.optLong("ts", System.currentTimeMillis()),
                speak = o.optBoolean("speak"),
                clientId = o.optString("client_id").ifEmpty { null },
                kind = o.optString("kind").ifEmpty { null },
                title = o.optString("title").ifEmpty { null },
            )
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** A session from GET /sessions. */
data class RemoteSession(
    val id: String,
    val title: String,
    val cwd: String,
    val folder: String,
    val updatedAt: Long,
    /** Open in the desktop app or a terminal: car turns continue in a copy. */
    val live: Boolean,
    val running: Boolean,
)

data class Folder(val cwd: String, val name: String)

data class SessionList(val sessions: List<RemoteSession>, val folders: List<Folder>)

object SessionListParser {
    fun parse(json: String): SessionList {
        val o = JSONObject(json)
        val arr = o.getJSONArray("sessions")
        val sessions = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            RemoteSession(
                id = s.getString("id"),
                title = s.getString("title"),
                cwd = s.getString("cwd"),
                folder = s.getString("folder"),
                updatedAt = s.optLong("updated_at"),
                live = s.optBoolean("live"),
                running = s.optBoolean("running"),
            )
        }
        val fa = o.optJSONArray("folders")
        val folders = if (fa == null) emptyList() else (0 until fa.length()).map { i ->
            val f = fa.getJSONObject(i)
            Folder(f.getString("cwd"), f.getString("folder"))
        }
        return SessionList(sessions, folders)
    }
}
