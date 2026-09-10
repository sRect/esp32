package com.sleepwell.provisioning.data

import org.junit.Assert.*
import org.junit.Test

class BleResponseFrameTest {
    @Test fun reconstructsChineseSsidAcrossEveryPossibleSplit() {
        val json = "{\"ssid\":\"卧室网络🌙\",\"password\":\"a\\nb\\\"c\"}\n"
        val bytes = json.toByteArray(Charsets.UTF_8)
        for (split in 1 until bytes.size) {
            val frame = BleResponseFrame()
            assertNull(frame.append(bytes.copyOfRange(0, split)))
            assertEquals(json, frame.append(bytes.copyOfRange(split, bytes.size)))
        }
    }

    @Test fun waitsForTerminatorAcrossManyDefaultMtuPackets() {
        val json = "{\"networks\":[" + (1..50).joinToString(",") { "{\"ssid\":\"网络$it\"}" } + "]}\n"
        val frame = BleResponseFrame()
        val chunks = json.toByteArray().toList().chunked(20)
        chunks.dropLast(1).forEach { assertNull(frame.append(it.toByteArray())) }
        assertEquals(json, frame.append(chunks.last().toByteArray()))
    }

    @Test fun rejectsOversizedUnterminatedResponses() {
        val frame = BleResponseFrame(10)
        assertNull(frame.append(ByteArray(10) { 65 }))
        assertThrows(IllegalStateException::class.java) { frame.append(byteArrayOf(65)) }
    }

    @Test fun rejectsDisconnectAndTrailingFrames() {
        assertThrows(IllegalStateException::class.java) { BleResponseFrame().append(byteArrayOf()) }
        assertThrows(IllegalStateException::class.java) { BleResponseFrame().append("{}\n{}\n".toByteArray()) }
    }
}
