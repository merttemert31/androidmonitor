package ai.arena.netscope.service

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ExportSharer {
    fun share(context: Context, filePath: String) {
        val file = File(filePath)
        require(file.exists()) { "Export file not found" }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.tcpdump.pcap")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(shareIntent, "PCAP paylaş"))
    }
}
