package com.sleepwell.provisioning.data

import java.io.ByteArrayOutputStream

/** Reassembles one bounded UTF-8 JSON line without decoding partial characters. */
internal class BleResponseFrame(private val maxBytes: Int = 65_536) {
    private val bytes = ByteArrayOutputStream()
    private var complete = false

    fun append(fragment: ByteArray): String? {
        check(!complete) { "已收到完整蓝牙响应" }
        check(fragment.isNotEmpty()) { "蓝牙连接已断开，请重新连接设备" }
        check(bytes.size() + fragment.size <= maxBytes) { "设备响应过长" }
        val terminator = fragment.indexOf('\n'.code.toByte())
        check(terminator == -1 || terminator == fragment.lastIndex) { "蓝牙响应格式错误" }
        bytes.write(fragment)
        if (terminator == -1) return null
        complete = true
        return bytes.toString("UTF-8")
    }
}
