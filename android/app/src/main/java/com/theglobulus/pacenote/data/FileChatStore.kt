package com.theglobulus.pacenote.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the conversations in a small JSON file so they survive restarts. */
class FileChatStore(private val file: File) : ChatStore {

    override fun load(): Snapshot? = try {
        if (!file.isFile) null else decode(JSONObject(file.readText()))
    } catch (e: Exception) {
        null
    }

    @Synchronized
    override fun save(snapshot: Snapshot) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encode(snapshot).toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun encode(s: Snapshot): JSONObject = JSONObject()
            .put("version", 2)
            .put("last_event_id", s.lastEventId ?: JSONObject.NULL)
            .put("conversations", JSONArray().apply {
                s.conversations.forEach { c ->
                    put(JSONObject().put("session_id", c.sessionId).put("title", c.title).put("last_active", c.lastActive))
                }
            })
            .put("messages", JSONArray().apply {
                s.messages.forEach { m ->
                    put(
                        JSONObject()
                            .put("id", m.id)
                            .put("session_id", m.sessionId)
                            .put("role", m.role.name)
                            .put("text", m.text)
                            .put("ts", m.ts)
                            .put("read", m.read)
                            // A send interrupted by the app dying counts as failed.
                            .put("status", (if (m.status == Status.SENDING) Status.FAILED else m.status).name)
                            .put("event_id", m.eventId ?: JSONObject.NULL)
                            .put("kind", m.kind ?: JSONObject.NULL)
                    )
                }
            })

        fun decode(o: JSONObject): Snapshot? {
            // Version 1 (single conversation, before sessions) isn't worth migrating.
            if (o.optInt("version", 1) < 2) return null
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(
                    id = m.getString("id"),
                    sessionId = m.getString("session_id"),
                    role = Role.valueOf(m.getString("role")),
                    text = m.getString("text"),
                    ts = m.getLong("ts"),
                    read = m.optBoolean("read", true),
                    status = Status.valueOf(m.optString("status", Status.RECEIVED.name)),
                    eventId = m.optStringOrNull("event_id"),
                    kind = m.optStringOrNull("kind"),
                )
            }
            val ca = o.optJSONArray("conversations") ?: JSONArray()
            val conversations = (0 until ca.length()).map { i ->
                val c = ca.getJSONObject(i)
                Conversation(c.getString("session_id"), c.getString("title"), c.optLong("last_active"))
            }
            return Snapshot(messages, o.optStringOrNull("last_event_id"), conversations)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}
