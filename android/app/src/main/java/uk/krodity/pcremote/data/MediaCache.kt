package uk.krodity.pcremote.data

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "MediaCache"

/** 1 MiB blocks: big enough that fetches amortise, small enough that a seek is cheap. */
private const val BLOCK = 1L shl 20

/** Total on-disk budget. Oldest streams are evicted first once this is passed. */
private const val CACHE_BUDGET = 3L shl 30   // 3 GiB

/**
 * A block cache over one remote file.
 *
 * The data file is created sparse and written at absolute offsets, so a viewer
 * who skips to the middle of a film does not force the leading gigabyte to be
 * downloaded. A sidecar bitmap records which blocks are actually present --
 * without it, a sparse file is indistinguishable from one full of legitimate
 * zero bytes.
 */
private class BlockFile(val data: File, val index: File, val size: Long) {

    private val blocks = ((size + BLOCK - 1) / BLOCK).toInt().coerceAtLeast(1)
    private val present = ByteArray((blocks + 7) / 8)
    private val lock = Any()

    init {
        if (index.exists() && index.length() == present.size.toLong()) {
            index.readBytes().copyInto(present)
        }
        if (!data.exists()) {
            RandomAccessFile(data, "rw").use { it.setLength(size) }
        }
    }

    fun has(block: Int) = (present[block / 8].toInt() shr (block % 8)) and 1 == 1

    private fun mark(block: Int) {
        present[block / 8] = (present[block / 8].toInt() or (1 shl (block % 8))).toByte()
        index.writeBytes(present)
    }

    fun read(block: Int, into: ByteArray): Int = synchronized(lock) {
        RandomAccessFile(data, "r").use { f ->
            val off = block * BLOCK
            f.seek(off)
            val want = minOf(BLOCK, size - off).toInt()
            f.readFully(into, 0, want)
            want
        }
    }

    fun write(block: Int, bytes: ByteArray, count: Int) = synchronized(lock) {
        RandomAccessFile(data, "rw").use { f ->
            f.seek(block * BLOCK)
            f.write(bytes, 0, count)
        }
        mark(block)
    }
}

/**
 * Serves remote files to whatever media player the user picks.
 *
 * Android will not let a third-party player authenticate to the agent, and
 * handing VLC the bearer token in a URL would leak it to an app we do not
 * control. So the player talks to this loopback server instead, and *it* holds
 * the token: the credential never leaves the process.
 *
 * Everything fetched is cached in blocks, so scrubbing backwards, re-opening a
 * file, or a player that probes the container header and then restarts (most
 * of them do) all read from disk rather than the network a second time.
 */
class MediaCacheServer(
    private val cacheDir: File,
    private val fetch: (path: String, start: Long, endInclusive: Long) -> ByteArray,
) {

    private data class Stream(val remotePath: String, val size: Long, val mime: String)

    private val streams = ConcurrentHashMap<String, Stream>()
    private val files = ConcurrentHashMap<String, BlockFile>()
    private val pool = Executors.newCachedThreadPool()

    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1

    /** Binds loopback only -- nothing off-device can reach this. */
    fun start() {
        if (server != null) return
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "media-cache") {
            val s = server ?: return@thread
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { serve(client) } }
            }
        }
        Log.i(TAG, "loopback media server on 127.0.0.1:$port")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** Registers a file and returns the URL to hand to a player. */
    fun urlFor(remotePath: String, size: Long, mime: String): String {
        start()
        val id = sha1(remotePath)
        streams[id] = Stream(remotePath, size, mime)
        // The filename is appended so players that sniff the extension (several
        // do, in preference to the Content-Type) still guess the right demuxer.
        val name = remotePath.substringAfterLast('/')
        return "http://127.0.0.1:$port/$id/${URLEncoder.encode(name, "UTF-8")}"
    }

    private fun blockFile(id: String, stream: Stream): BlockFile = files.getOrPut(id) {
        val dir = File(cacheDir, "media").apply { mkdirs() }
        evictIfNeeded(dir)
        BlockFile(File(dir, "$id.dat"), File(dir, "$id.idx"), stream.size)
    }

    /** Drops whole streams, oldest first, once the budget is exceeded. */
    private fun evictIfNeeded(dir: File) {
        val all = dir.listFiles()?.filter { it.name.endsWith(".dat") } ?: return
        var total = all.sumOf { it.length() }
        if (total <= CACHE_BUDGET) return
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BUDGET) break
            total -= f.length()
            val id = f.nameWithoutExtension
            files.remove(id)
            f.delete()
            File(dir, "$id.idx").delete()
            Log.i(TAG, "evicted cached stream $id")
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private fun serve(client: Socket): Unit = client.use { sock ->
        sock.soTimeout = 30_000
        val input = sock.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        val headers = buildMap {
            while (true) {
                val line = input.readLine()
                if (line.isNullOrBlank()) break
                val i = line.indexOf(':')
                if (i > 0) put(line.take(i).lowercase(), line.substring(i + 1).trim())
            }
        }

        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: return
        val id = parts.getOrNull(1)?.removePrefix("/")?.substringBefore('/').orEmpty()
        val stream = streams[id] ?: return respond(sock, 404, "no such stream")

        val size = stream.size
        var start = 0L
        var end = size - 1
        var partial = false
        headers["range"]?.takeIf { it.startsWith("bytes=") }?.let { r ->
            val spec = r.removePrefix("bytes=").substringBefore(',').trim()
            val first = spec.substringBefore('-')
            val last = spec.substringAfter('-', "")
            if (first.isNotEmpty()) {
                start = first.toLongOrNull() ?: 0
                end = last.toLongOrNull() ?: (size - 1)
            } else if (last.isNotEmpty()) {
                start = (size - (last.toLongOrNull() ?: 0)).coerceAtLeast(0)
            }
            partial = true
        }
        if (start >= size) {
            return respond(sock, 416, "range not satisfiable",
                extra = "Content-Range: bytes */$size\r\n")
        }
        end = end.coerceAtMost(size - 1)
        val length = end - start + 1

        val head = StringBuilder()
            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ${stream.mime}\r\n")
            .append("Content-Length: $length\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .apply { if (partial) append("Content-Range: bytes $start-$end/$size\r\n") }
            .append("Connection: close\r\n\r\n")

        val out = sock.getOutputStream()
        out.write(head.toString().toByteArray())
        if (method == "HEAD") { out.flush(); return }

        val bf = blockFile(id, stream)
        val buf = ByteArray(BLOCK.toInt())
        var pos = start
        try {
            while (pos <= end) {
                val block = (pos / BLOCK).toInt()
                val blockStart = block * BLOCK
                val blockLen = minOf(BLOCK, size - blockStart).toInt()

                if (bf.has(block)) {
                    bf.read(block, buf)
                } else {
                    val got = fetch(stream.remotePath, blockStart, blockStart + blockLen - 1)
                    got.copyInto(buf, 0, 0, minOf(got.size, blockLen))
                    bf.write(block, buf, blockLen)
                }

                val from = (pos - blockStart).toInt()
                val to = minOf(blockLen.toLong(), end - blockStart + 1).toInt()
                out.write(buf, from, to - from)
                pos = blockStart + to
            }
            out.flush()
        } catch (e: Exception) {
            // Players routinely abandon a connection when the user seeks.
            Log.d(TAG, "stream closed early: ${e.message}")
        }
    }

    private fun respond(sock: Socket, code: Int, msg: String, extra: String = "") {
        runCatching {
            sock.getOutputStream().write(
                ("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\n$extra" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

// ── media type table ────────────────────────────────────────────────────────
private val MEDIA_MIME = mapOf(
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska",
    "webm" to "video/webm", "avi" to "video/x-msvideo", "mov" to "video/quicktime",
    "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv", "mpg" to "video/mpeg",
    "mpeg" to "video/mpeg", "ts" to "video/mp2t", "m2ts" to "video/mp2t",
    "3gp" to "video/3gpp", "ogv" to "video/ogg",
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/wav",
    "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus",
    "m4a" to "audio/mp4", "aac" to "audio/aac", "wma" to "audio/x-ms-wma",
    "mka" to "audio/x-matroska", "aiff" to "audio/aiff", "ape" to "audio/x-ape",
)

/** The MIME type to advertise, or null if this is not playable media. */
fun mediaMimeOf(name: String): String? =
    MEDIA_MIME[name.substringAfterLast('.', "").lowercase()]

fun isMedia(name: String) = mediaMimeOf(name) != null
