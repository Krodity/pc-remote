package uk.krodity.pcremote.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()
private val OCTET = "application/octet-stream".toMediaType()

class AgentException(message: String) : Exception(message)

/**
 * Talks to pc-agent over both of its ports.
 *
 * REST covers anything request/response -- listing, reading and writing files,
 * system stats. The socket covers anything that has to be live: pointer
 * deltas, keystrokes, and the terminal's byte stream in both directions.
 */
class AgentClient(private val pairing: Pairing) {

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // The socket carries its own heartbeat; this keeps a dozing phone's
        // connection from being reaped by an intermediate NAT.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── REST ─────────────────────────────────────────────────────────────────
    private fun url(path: String, query: Map<String, String> = emptyMap()) =
        (pairing.httpBase + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private fun Request.Builder.auth() =
        header("Authorization", "Bearer ${pairing.token}")

    private fun <T> Response.decode(cls: Class<T>): T {
        val body = body?.string().orEmpty()
        if (!isSuccessful) {
            val msg = runCatching {
                gson.fromJson(body, JsonObject::class.java).get("error").asString
            }.getOrNull() ?: "HTTP $code"
            throw AgentException(msg)
        }
        return gson.fromJson(body, cls)
    }

    private suspend fun <T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        cls: Class<T>,
    ): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(path, query)).auth().build())
            .execute().use { it.decode(cls) }
    }

    private suspend fun <T> post(
        path: String,
        body: Map<String, Any?>,
        cls: Class<T>,
    ): T = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).auth()
            .post(gson.toJson(body).toRequestBody(JSON)).build()
        http.newCall(req).execute().use { it.decode(cls) }
    }

    suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        http.newCall(Request.Builder().url(url("/api/ping")).auth().build())
            .execute().use {
                if (!it.isSuccessful) throw AgentException("HTTP ${it.code}")
                it.body?.string()
            }
        (System.nanoTime() - t0) / 1_000_000
    }

    suspend fun sysinfo() = get("/api/sysinfo", cls = SysInfo::class.java)

    suspend fun places() =
        get("/api/fs/places", cls = PlacesResponse::class.java).places

    suspend fun list(path: String) =
        get("/api/fs/list", mapOf("path" to path), FsListing::class.java)

    suspend fun read(path: String) =
        get("/api/fs/read", mapOf("path" to path), FsRead::class.java)

    suspend fun write(path: String, text: String) =
        post("/api/fs/write", mapOf("path" to path, "text" to text), JsonObject::class.java)

    suspend fun mkdir(path: String) =
        post("/api/fs/mkdir", mapOf("path" to path), JsonObject::class.java)

    suspend fun create(path: String) =
        post("/api/fs/create", mapOf("path" to path), JsonObject::class.java)

    suspend fun rename(path: String, name: String) =
        post("/api/fs/rename", mapOf("path" to path, "name" to name), JsonObject::class.java)

    suspend fun copy(path: String, to: String) =
        post("/api/fs/copy", mapOf("path" to path, "to" to to), JsonObject::class.java)

    /** Deletes to the freedesktop trash unless [permanent]. */
    suspend fun delete(path: String, permanent: Boolean = false) =
        post("/api/fs/delete", mapOf("path" to path, "permanent" to permanent),
            JsonObject::class.java)

    suspend fun exec(cmd: String, cwd: String? = null) =
        post("/api/exec", mapOf("cmd" to cmd, "cwd" to cwd), ExecResult::class.java)

    suspend fun openOnPc(path: String) =
        post("/api/open", mapOf("path" to path), JsonObject::class.java)

    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(url("/api/fs/download", mapOf("path" to path)))
                .auth().build()
        ).execute().use {
            if (!it.isSuccessful) throw AgentException("HTTP ${it.code}")
            it.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun upload(path: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/fs/upload", mapOf("path" to path)))
            .auth().post(bytes.toRequestBody(OCTET)).build()
        http.newCall(req).execute().use {
            if (!it.isSuccessful) throw AgentException("HTTP ${it.code}")
        }
    }

    // ── WebSocket ────────────────────────────────────────────────────────────
    /**
     * Opens the live channel.
     *
     * Returns a [Channel] whose `send` is fire-and-forget: pointer deltas are
     * worthless a frame later, so a dropped one must never block the next.
     */
    fun connect(
        onMessage: (JsonObject) -> Unit,
        onOpen: () -> Unit,
        onClosed: (String?) -> Unit,
    ): Channel {
        val req = Request.Builder()
            .url("${pairing.wsBase}/?token=${pairing.token}")
            .build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { gson.fromJson(text, JsonObject::class.java) }
                    .getOrNull()?.let(onMessage)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) =
                onClosed(t.message ?: "connection failed")

            override fun onClosed(ws: WebSocket, code: Int, reason: String) =
                onClosed(reason.ifBlank { null })
        })
        return Channel(ws, gson)
    }

    class Channel(private val ws: WebSocket, private val gson: Gson) {
        private fun send(vararg pairs: Pair<String, Any?>) {
            ws.send(gson.toJson(pairs.toMap()))
        }

        fun move(dx: Float, dy: Float) = send("t" to "mouse", "dx" to dx, "dy" to dy)
        fun button(b: String, down: Boolean) = send("t" to "btn", "b" to b, "down" to down)
        fun click(b: String, n: Int = 1) = send("t" to "click", "b" to b, "n" to n)
        fun scroll(dy: Int, dx: Int = 0) = send("t" to "scroll", "dy" to dy, "dx" to dx)

        fun key(k: String) = send("t" to "key", "k" to k)
        fun combo(k: String) = send("t" to "combo", "k" to k)
        fun modifier(k: String, down: Boolean) = send("t" to "mod", "k" to k, "down" to down)
        fun text(s: String) = send("t" to "text", "s" to s)
        fun releaseAll() = send("t" to "release")

        fun ptyOpen(cols: Int, rows: Int, cwd: String? = null) =
            send("t" to "pty.open", "cols" to cols, "rows" to rows, "cwd" to cwd)

        fun ptyIn(d: String) = send("t" to "pty.in", "d" to d)
        fun ptyResize(cols: Int, rows: Int) =
            send("t" to "pty.size", "cols" to cols, "rows" to rows)

        fun ptySignal(sig: String) = send("t" to "pty.signal", "sig" to sig)
        fun ping(id: Long) = send("t" to "ping", "id" to id)

        fun close() {
            runCatching { ws.close(1000, "bye") }
        }
    }
}

/** Probe a host/token pair without persisting it -- used by the pair screen. */
suspend fun verify(host: String, token: String): SysInfo =
    AgentClient(Pairing(host, token)).sysinfo()
