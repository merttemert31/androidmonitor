package ai.arena.netscope.service

import android.content.Context
import ai.arena.netscope.model.CapturedPacket
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PcapWriter {
    fun export(
        context: Context,
        packets: List<CapturedPacket>,
        filePrefix: String = "netscope",
    ): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val exportDir = File(baseDir, "exports").apply { mkdirs() }
        val filename = "${sanitize(filePrefix)}-${timestampText()}.pcap"
        val file = File(exportDir, filename)

        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            writeIntLe(out, 0xA1B2C3D4.toInt())
            writeShortLe(out, 2)
            writeShortLe(out, 4)
            writeIntLe(out, 0)
            writeIntLe(out, 0)
            writeIntLe(out, 65_535)
            writeIntLe(out, 101)

            packets.forEach { packet ->
                val length = packet.rawData.size
                writeIntLe(out, (packet.timestamp / 1000L).toInt())
                writeIntLe(out, ((packet.timestamp % 1000L) * 1000L).toInt())
                writeIntLe(out, length)
                writeIntLe(out, length)
                out.write(packet.rawData)
            }
        }

        return file
    }

    private fun sanitize(input: String): String {
        return input.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "netscope" }
    }

    private fun timestampText(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    private fun writeIntLe(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value ushr 8) and 0xFF)
        out.writeByte((value ushr 16) and 0xFF)
        out.writeByte((value ushr 24) and 0xFF)
    }

    private fun writeShortLe(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value ushr 8) and 0xFF)
    }
}
