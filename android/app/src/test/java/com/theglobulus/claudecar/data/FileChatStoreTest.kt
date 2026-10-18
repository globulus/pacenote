package com.theglobulus.claudecar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileChatStoreTest {

    @Test
    fun `round trips a snapshot and turns interrupted sends into failures`() {
        val file = File(Files.createTempDirectory("chat").toFile(), "chat.json")
        val messages = listOf(
            ChatMessage("c-1", "s1", Role.USER, "hello", 1, status = Status.SENDING),
            ChatMessage("b.2", "s1", Role.CLAUDE, "hi \"there\"\nnew line", 2, read = false, eventId = "b.2"),
            ChatMessage("local-1", HUB, Role.SYSTEM, "offline", 3, kind = "local"),
        )
        val conversations = listOf(Conversation("s1", "Refund bug", 2), Conversation(HUB, HUB_TITLE, 3))
        FileChatStore(file).save(Snapshot(messages, "b.2", conversations))

        val loaded = FileChatStore(file).load()!!
        assertEquals("b.2", loaded.lastEventId)
        assertEquals(Status.FAILED, loaded.messages[0].status)
        assertEquals(messages[1], loaded.messages[1])
        assertEquals(messages[2], loaded.messages[2])
        assertEquals(conversations, loaded.conversations)
    }

    @Test
    fun `missing, corrupt or old-format files load nothing`() {
        val dir = Files.createTempDirectory("chat").toFile()
        assertNull(FileChatStore(File(dir, "none.json")).load())
        assertNull(FileChatStore(File(dir, "bad.json").apply { writeText("{not json") }).load())
        val v1 = File(dir, "v1.json").apply { writeText("""{"last_event_id":"x.1","messages":[]}""") }
        assertNull(FileChatStore(v1).load())
    }
}
