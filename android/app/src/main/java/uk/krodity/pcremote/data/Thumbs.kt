package uk.krodity.pcremote.data

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private val IMAGE_EXT = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "avif",
    "heic", "heif", "ico", "svg",
)
private val VIDEO_EXT = setOf(
    "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "mpg",
    "mpeg", "ts", "m2ts", "3gp", "ogv",
)

/** Whether the agent can render a preview for this name. Mirrors the agent's table. */
fun canThumb(name: String): Boolean {
    val e = name.substringAfterLast('.', "").lowercase()
    return e in IMAGE_EXT || e in VIDEO_EXT || e == "pdf"
}

/**
 * Fetches and caches directory thumbnails.
 *
 * Two layers, because a grid scroll is brutal on both: an in-memory LRU so
 * scrolling back up is instant, and a disk cache so re-entering a folder does
 * not re-fetch. The agent renders and caches them too, but a round trip per
 * tile over the tailnet is still worth avoiding.
 *
 * Concurrency is capped: firing forty simultaneous requests at the agent makes
 * the first visible tile arrive later, not sooner.
 */
class ThumbLoader(private val cacheDir: File) {

    private val memory = object : LruCache<String, ImageBitmap>(64) {}
    private val gate = Semaphore(4)

    /** Null means "no preview available" — the caller should show an icon. */
    suspend fun load(
        client: AgentClient,
        path: String,
        mtime: Long,
        size: Int = 256,
    ): ImageBitmap? {
        // mtime is in the key so an edited file re-renders rather than showing
        // a stale preview.
        val key = "${path.hashCode()}_${mtime}_$size"
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "thumbs").apply { mkdirs() }
            val file = File(dir, "$key.jpg")

            val bytes = if (file.exists()) {
                file.readBytes()
            } else {
                gate.withPermit {
                    // Another coroutine may have won the race while we waited.
                    memory.get(key)?.let { return@withContext it }
                    val fetched = runCatching {
                        client.thumb(path, size)
                    }.getOrNull() ?: return@withContext null
                    runCatching { file.writeBytes(fetched) }
                    fetched
                }
            }

            val bmp = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull() ?: return@withContext null

            bmp.asImageBitmap().also { memory.put(key, it) }
        }
    }
}
