package uk.krodity.pcremote.ui

import androidx.compose.ui.graphics.Color
import uk.krodity.pcremote.ui.theme.P
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("MMM dd", Locale.getDefault())
private val yearFmt = SimpleDateFormat("MMM dd yyyy", Locale.getDefault())

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "${v.toInt()} ${units[i]}"
    else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** Recent files show a bare date; anything from another year carries it. */
fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val d = Date(epochSeconds * 1000)
    val now = System.currentTimeMillis()
    val elapsed = now - d.time
    return if (elapsed in 0..(330L * 24 * 3600 * 1000)) dayFmt.format(d)
    else yearFmt.format(d)
}

fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

private val codeExts = setOf(
    "py", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "c", "h", "cpp", "rs",
    "go", "rb", "lua", "sh", "bash", "zsh", "fish", "json", "yaml", "yml",
    "toml", "xml", "html", "css", "sql", "vim",
)
private val docExts = setOf("md", "txt", "log", "rst", "conf", "cfg", "ini", "service")
private val archiveExts = setOf(
    "zip", "tar", "gz", "xz", "zst", "bz2", "7z", "rar", "iso", "img", "apk",
    "deb", "rpm", "pkg", "AppImage",
)
private val mediaExts = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "svg", "mp4", "mkv", "webm", "mp3",
    "flac", "wav", "opus", "pdf",
)

fun extensionOf(name: String) =
    if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

/**
 * Colour carries the file's kind, as in the mock: amber for directories, green
 * for code, red for things that install or unpack, purple for media.
 */
fun fileColor(name: String, isDir: Boolean): Color {
    if (isDir) return P.amber
    return when (extensionOf(name)) {
        in codeExts -> P.green
        in archiveExts -> P.red
        in docExts -> P.sub
        in mediaExts -> P.purple
        else -> P.sub
    }
}

fun isTextLike(name: String): Boolean {
    val e = extensionOf(name)
    return e in codeExts || e in docExts || e.isEmpty()
}
