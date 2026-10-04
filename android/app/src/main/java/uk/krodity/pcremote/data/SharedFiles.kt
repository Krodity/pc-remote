package uk.krodity.pcremote.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Above this a file is streamed rather than copied.
 *
 * Anything this big is a film, and handing a film to a `content://` URI means
 * downloading the whole thing before the player draws a frame — which is the
 * exact problem [MediaCacheServer] exists to avoid.
 */
private const val COPY_MAX_BYTES = 128L shl 20

/** Keep the hand-off directory from growing without bound. */
private const val SHARED_BUDGET = 256L shl 20

/**
 * Materialises a remote file locally so another app can be handed it.
 *
 * Video and audio go out as a loopback URL, because a player will stream from
 * one and never needs the whole file. Stills cannot: `ACTION_VIEW` on an
 * `http://127.0.0.1/…` image resolves to nothing at all, because gallery apps
 * register for `content://` and `file://` and leave `http` to the browser. So
 * a picture is copied into a FileProvider-backed directory and handed over as
 * a `content://` URI with a read grant, which is the form every image app on
 * the phone actually claims — and therefore the form Android can remember a
 * default for.
 */
object SharedFiles {

    fun canCopy(size: Long) = size in 1..COPY_MAX_BYTES

    /** Downloads [remotePath] into the shared cache and returns its URI. */
    suspend fun materialise(
        ctx: Context,
        client: AgentClient,
        remotePath: String,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        // The remote name is kept: several apps show it as the title, and some
        // sniff the extension in preference to the type they were handed.
        val name = remotePath.substringAfterLast('/').ifBlank { "file" }
        val file = File(dir, name)

        if (!file.exists() || file.length() == 0L) {
            prune(dir)
            // Written to a sibling first: a reader that arrives while the copy
            // is in flight would otherwise be handed a truncated image.
            val tmp = File(dir, ".$name.part")
            tmp.writeBytes(client.downloadBlocking(remotePath))
            tmp.renameTo(file)
        }
        file.setLastModified(System.currentTimeMillis())

        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    /** Drops the least recently handed-out files once the budget is exceeded. */
    private fun prune(dir: File) {
        val all = dir.listFiles()?.toList() ?: return
        var total = all.sumOf { it.length() }
        if (total <= SHARED_BUDGET) return
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= SHARED_BUDGET) break
            total -= f.length()
            f.delete()
        }
    }
}
