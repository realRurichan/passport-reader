package io.github.realrurichan.twowaypermitreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket

class ApduRelay(private val onLog: (String) -> Unit) {
    suspend fun serve(tag: Tag, port: Int = 8983) = withContext(Dispatchers.IO) {
        val isoDep = requireNotNull(IsoDep.get(tag)) { "芯片不支持 ISO-DEP" }
        isoDep.connect()
        isoDep.timeout = 30000
        onLog("已连接芯片，等待电脑连接（端口 $port）")
        ServerSocket(port).use { server ->
            while (true) {
                server.accept().use { socket ->
                    socket.tcpNoDelay = true
                    onLog("电脑已连接，开始转发 APDU")
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
                    val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.US_ASCII))
                    while (true) {
                        val line = reader.readLine() ?: break
                        val hex = line.replace(" ", "").trim()
                        if (hex.isEmpty() || hex.equals("QUIT", ignoreCase = true)) continue
                        try {
                            val response = isoDep.transceive(hex.hexToBytes())
                            writer.write(response.toHex())
                        } catch (error: Exception) {
                            onLog("转发失败：${error.message}")
                            writer.write("ERROR ${error.message?.replace('\n', ' ')}")
                            writer.newLine()
                            writer.flush()
                            return@withContext
                        }
                        writer.newLine()
                        writer.flush()
                    }
                    onLog("电脑断开，继续监听")
                }
            }
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "奇数长度十六进制：$this" }
        return ByteArray(length / 2) { index ->
            ((Character.digit(this[index * 2], 16) shl 4) or Character.digit(this[index * 2 + 1], 16)).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { String.format("%02X", it) }
}
