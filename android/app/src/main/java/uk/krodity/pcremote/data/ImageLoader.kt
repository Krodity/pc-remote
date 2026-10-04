package uk.krodity.pcremote.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ImageLoader"

/**
 * Formats Android's own decoders handle.
 *
 * Everything outside this set — SVG, TIFF, ICO — has to be rendered on the PC
 * instead, because `BitmapFactory` simply returns null for them and the failure
 * is silent.
 */
private val NATIVE_DECODE = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
)

/**
 * Above this, do not pull the original.
 *
 * The ceiling is about the transfer and the decode together: `~/Pictures` here
 * holds 235 MP upscayl PNGs, and asking the phone to hold one is hopeless
 * however it is sampled. Those go through the agent's renderer, which already
 * knows to fall back to ImageMagick for exactly this case.
 */
private const val FULL_MAX_BYTES = 32L shl 20

/**
 * Loads images at viewing size rather than tile size.
 *
 * Two sources, picked per file:
 *
 * - **the original**, when the phone can decode the format and the file is
 *   small enough to be worth moving. Sub-sampled during decode, so a 24 MP
 *   photo costs a screen-sized bitmap and not 96 MB of heap. This is the path
 *   that makes pinch-zoom show real detail.
 * - **an agent render**, otherwise. `/api/fs/thumb` will produce a JPEG at any
 *   size up to 4096 from anything it can open, which covers both the formats
 *   Android cannot read and the files too large to send.
 *
 * The cache is bounded by *bytes*, not entries: a full-screen bitmap is around
 * 20 MB, so counting entries would let three of them exhaust the heap.
 */
class FullImageLoader {

    private val memory = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(16 shl 20)
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) =
            value.width * value.height * 4
    }

    /** Null means nothing could be decoded — the caller should say so. */
    suspend fun load(
        client: AgentClient,
        path: String,
        bytes: Long,
        mtime: Long,
        maxPx: Int,
    ): ImageBitmap? {
        val key = "${path.hashCode()}_${mtime}_$maxPx"
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            val native = ext in NATIVE_DECODE && bytes in 1..FULL_MAX_BYTES

            val bmp = (if (native) decodeOriginal(client, path, maxPx) else null)
                ?: decodeRendered(client, path, maxPx)
                ?: return@withContext null

            bmp.asImageBitmap().also { memory.put(key, it) }
        }
    }

    private fun decodeOriginal(client: AgentClient, path: String, maxPx: Int): Bitmap? =
        runCatching {
            // Blocking on purpose: already on Dispatchers.IO, and the suspend
            // `download` would need a scope we do not have here.
            val raw = client.downloadBlocking(path)
            sampled(raw, maxPx)
        }.onFailure { Log.d(TAG, "original decode failed for $path: ${it.message}") }
            .getOrNull()

    private suspend fun decodeRendered(client: AgentClient, path: String, maxPx: Int): Bitmap? =
        runCatching {
            val jpeg = client.thumb(path, maxPx.coerceIn(48, 4096))
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }.onFailure { Log.d(TAG, "agent render failed for $path: ${it.message}") }
            .getOrNull()

    /**
     * Decode no larger than we will ever draw.
     *
     * `inSampleSize` only halves, so the result can be up to 2x [maxPx] on a
     * side. That slack is deliberate — rounding the other way would blur every
     * image whose dimensions sit just above a power of two.
     */
    private fun sampled(raw: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }
}
