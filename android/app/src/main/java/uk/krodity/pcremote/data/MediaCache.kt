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

/**
 * How much of any one file is held on disk at a time.
 *
 * This is a streaming buffer, not a download: 96 blocks is a comfortable
 * rewind/re-buffer window around the playhead, and it is the *whole* footprint
 * whether the file is 40 MB or 40 GB.
 */
private const val WINDOW_BLOCKS = 96

/** Blocks to pull ahead of the playhead so playback does not stall on a read. */
private const val READAHEAD_BLOCKS = 4

/** Across all streams. Bounded per-file already, so this only limits how many. */
private const val TOTAL_BUDGET = 512L shl 20   // 512 MiB

/**
 * A fixed-size sliding window over one remote file.
 *
 * The earlier version kept every block it had ever fetched in a sparse
 * full-length file, which meant watching a film to the end left the whole film
 * on the phone. This keeps a bounded set of slots instead and recycles the
 * least recently used one, so the on-disk cost is [WINDOW_BLOCKS] MiB no matter
 * how long the file is or how long you watch.
 *
 * The block at each end is pinned. Container indexes live at the start
 * (Matroska cues, a faststart MP4's moov) or the very end (a non-faststart
 * MP4's moov), and players re-read them on every seek -- letting those fall out
 * of the window turns each scrub into two extra round trips.
 */
private class BlockStore(private val dataFile: File, val size: Long) {

    val totalBlocks = ((size + BLOCK - 1) / BLOCK).toInt().coerceAtLeast(1)
    private val capacity = minOf(totalBlocks, WINDOW_BLOCKS)

    /** block index -> slot index, in access order so the eldest is the LRU. */
    private val slots = LinkedHashMap<Int, Int>(16, 0.75f, true)
    private val free = ArrayDeque<Int>().apply { repeat(capacity) { addLast(it) } }
    private val lock = Any()

    private val pinned: Set<Int> =
        if (capacity >= 8) setOf(0, totalBlocks - 1) else emptySet()

    init {
        RandomAccessFile(dataFile, "rw").use { it.setLength(capacity * BLOCK) }
    }

    fun lengthOf(block: Int) = minOf(BLOCK, size - block * BLOCK).toInt()

    fun has(block: Int) = synchronized(lock) { slots.containsKey(block) }

    /** Returns bytes read, or null if the block is not resident. */
    fun read(block: Int, into: ByteArray): Int? = synchronized(lock) {
        val slot = slots[block] ?: return null       // also marks it used
        val want = lengthOf(block)
        RandomAccessFile(dataFile, "r").use { f ->
            f.seek(slot * BLOCK)
            f.readFully(into, 0, want)
        }
        want
    }

    fun write(block: Int, bytes: ByteArray, count: Int) = synchronized(lock) {
        if (slots.containsKey(block)) return
        val slot = free.removeFirstOrNull() ?: recycle() ?: return
        RandomAccessFile(dataFile, "rw").use { f ->
            f.seek(slot * BLOCK)
            f.write(bytes, 0, count)
        }
        slots[block] = slot
    }

    /** Frees the least recently used unpinned slot. */
    private fun recycle(): Int? {
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in pinned) continue
            it.remove()
            return e.value
        }
        return null
    }

    fun delete() {
        synchronized(lock) {
            slots.clear()
            free.clear()
            dataFile.delete()
        }
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
 * Bytes are pulled a block at a time, only as far ahead as playback needs, and
 * held in a bounded window -- so a long film streams rather than downloading.
 */
class MediaCacheServer(
    private val cacheDir: File,
    private val fetch: (path: String, start: Long, endInclusive: Long) -> ByteArray,
) {

    private data class Stream(val remotePath: String, val size: Long, val mime: String)

    private val streams = ConcurrentHashMap<String, Stream>()
    private val stores = ConcurrentHashMap<String, BlockStore>()
    private val pool = Executors.newCachedThreadPool()
    /** Single thread: read-ahead must never outrun or outrank the live read. */
    private val prefetcher = Executors.newSingleThreadExecutor()
    private val prefetching = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1

    /** Live connection count and last-byte time, so a service can tell when to stop. */
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastActivity = System.currentTimeMillis()

    /** Nothing is reading and nothing has for [graceMs]. */
    fun isIdle(graceMs: Long = 60_000) =
        active.get() == 0 && System.currentTimeMillis() - lastActivity > graceMs

    /** Binds loopback only -- nothing off-device can reach this. */
    fun start() {
        if (server != null) return
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "media-cache") {
            val s = server ?: return@thread
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                pool.execute {
                    active.incrementAndGet()
                    try { runCatching { serve(client) } }
                    finally {
                        active.decrementAndGet()
                        lastActivity = System.currentTimeMillis()
                    }
                }
            }
        }
        Log.i(TAG, "loopback media server on 127.0.0.1:$port")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        // Window buffers are scratch space, not a library. Nothing here is
        // worth keeping once the app is done with it.
        stores.values.forEach { it.delete() }
        stores.clear()
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

    private fun storeFor(id: String, stream: Stream): BlockStore = stores.getOrPut(id) {
        val dir = File(cacheDir, "media").apply { mkdirs() }
        pruneOldStreams(dir)
        BlockStore(File(dir, "$id.buf"), stream.size)
    }

    /** Drops whole stream buffers, oldest first, once the total is exceeded. */
    private fun pruneOldStreams(dir: File) {
        // Sparse full-length files and index sidecars from the previous
        // caching scheme; harmless, but they can be gigabytes each.
        dir.listFiles()?.filter { it.name.endsWith(".dat") || it.name.endsWith(".idx") }
            ?.forEach { it.delete() }

        val all = dir.listFiles()?.filter { it.name.endsWith(".buf") } ?: return
        var total = all.sumOf { it.length() }
        if (total <= TOTAL_BUDGET) return
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= TOTAL_BUDGET) break
            total -= f.length()
            stores.remove(f.nameWithoutExtension)
            f.delete()
            Log.i(TAG, "dropped stream buffer ${f.name}")
        }
    }

    /** Pulls one block into the window, unless it is already resident. */
    private fun ensure(store: BlockStore, stream: Stream, block: Int, buf: ByteArray): Int {
        store.read(block, buf)?.let { return it }
        val want = store.lengthOf(block)
        val offset = block * BLOCK
        val got = fetch(stream.remotePath, offset, offset + want - 1)
        val n = minOf(got.size, want)
        got.copyInto(buf, 0, 0, n)
        store.write(block, buf, n)
        return n
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

        val store = storeFor(id, stream)
        val buf = ByteArray(BLOCK.toInt())
        var pos = start
        try {
            while (pos <= end) {
                val block = (pos / BLOCK).toInt()
                val blockStart = block * BLOCK
                val blockLen = ensure(store, stream, block, buf)

                queueReadahead(store, stream, block)

                val from = (pos - blockStart).toInt()
                val to = minOf(blockLen.toLong(), end - blockStart + 1).toInt()
                if (to <= from) break
                out.write(buf, from, to - from)
                pos = blockStart + to
                lastActivity = System.currentTimeMillis()
            }
            out.flush()
        } catch (e: Exception) {
            // Players routinely abandon a connection when the user seeks.
            Log.d(TAG, "stream closed early: ${e.message}")
        }
    }

    /**
     * Warm the next few blocks behind the playhead.
     *
     * Bounded to [READAHEAD_BLOCKS] and run on one thread, so it stays well
     * inside the window and cannot evict what the player is about to read.
     */
    private fun queueReadahead(store: BlockStore, stream: Stream, from: Int) {
        val wanted = (from + 1..from + READAHEAD_BLOCKS)
            .filter { it < store.totalBlocks && !store.has(it) }
        if (wanted.isEmpty()) return
        // One task in flight at a time. This runs once per block served, so
        // without the guard a fast reader queues hundreds of redundant jobs.
        if (!prefetching.compareAndSet(false, true)) return
        prefetcher.execute {
            try {
                val scratch = ByteArray(BLOCK.toInt())
                for (b in wanted) {
                    if (store.has(b)) continue
                    if (runCatching { ensure(store, stream, b, scratch) }.isFailure) break
                }
            } finally {
                prefetching.set(false)
            }
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
