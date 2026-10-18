package com.theglobulus.claudecar.data

enum class Role { USER, CLAUDE, SYSTEM }

enum class Status { SENDING, SENT, FAILED, RECEIVED }

/** The Claude Car command chat ("list sessions", "open payments"). */
const val HUB = "hub"
const val HUB_TITLE = "Claude Car"

/**
 * One line of a car conversation. [sessionId] is the Claude Code session (or
 * [HUB]). Outgoing messages use the client id we POSTed as [id]; messages from
 * the Mac use the bridge event id.
 */
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    val text: String,
    val ts: Long,
    val read: Boolean = true,
    val status: Status = Status.RECEIVED,
    val eventId: String? = null,
    val kind: String? = null,
) {
    val incoming: Boolean get() = role != Role.USER
}

/** A session that has a conversation on the phone (and in the car). */
data class Conversation(val sessionId: String, val title: String, val lastActive: Long)
