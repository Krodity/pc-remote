package uk.krodity.pcremote.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.krodity.pcremote.data.AgentClient
import uk.krodity.pcremote.data.FsEntry
import uk.krodity.pcremote.data.Link
import uk.krodity.pcremote.data.Pairing
import uk.krodity.pcremote.data.Place
import uk.krodity.pcremote.data.SettingsRepo
import uk.krodity.pcremote.data.MediaStreamService
import uk.krodity.pcremote.data.MediaStreams
import uk.krodity.pcremote.data.SysInfo
import uk.krodity.pcremote.data.ThumbLoader
import uk.krodity.pcremote.data.mediaMimeOf
import uk.krodity.pcremote.data.verify

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepo(app)

    var pairing by mutableStateOf(Pairing())
        private set
    var client: AgentClient? = null
        private set
    var channel: AgentClient.Channel? = null
        private set

    var link by mutableStateOf(Link.OFFLINE)
        private set
    var pingMs by mutableStateOf<Long?>(null)
        private set
    var info by mutableStateOf<SysInfo?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    /** false = hand tapped files to xdg-open on the PC; true = edit them here. */
    var openInApp by mutableStateOf(false)
        private set

    /** Detail list vs thumbnail grid in the file browser. */
    var gridView by mutableStateOf(false)
        private set

    val thumbs = ThumbLoader(app.cacheDir)

    // ── files ────────────────────────────────────────────────────────────────
    var cwd by mutableStateOf("")
        private set
    val entries = mutableStateListOf<FsEntry>()
    var places by mutableStateOf<List<Place>>(emptyList())
        private set
    var filesLoading by mutableStateOf(false)
        private set
    var filesError by mutableStateOf<String?>(null)
        private set
    private val history = ArrayList<String>()
    private var histIdx = -1
    val canBack get() = histIdx > 0
    val canForward get() = histIdx in 0 until history.size - 1

    // ── keys ─────────────────────────────────────────────────────────────────
    val heldModifiers = mutableStateListOf<String>()
    val keyLog = mutableStateListOf<String>()

    // ── shell ────────────────────────────────────────────────────────────────
    val term = AnsiTerminal()
    var termRevision by mutableIntStateOf(0)
        private set
    var ptyReady by mutableStateOf(false)
        private set
    val cmdHistory = mutableStateListOf<String>()

    private var pingJob: Job? = null

    /**
     * Loopback server that feeds cached, seekable streams to phone media
     * players. Created lazily -- most sessions never open a video.
     */
    private val media
        get() = MediaStreams.server(getApplication<Application>().cacheDir) { path, start, end ->
            val c = client ?: throw IllegalStateException("not connected")
            c.fetchRangeBlocking(path, start, end)
        }

    init {
        viewModelScope.launch {
            settings.openInApp.collect { openInApp = it }
        }
        viewModelScope.launch {
            settings.gridView.collect { gridView = it }
        }
        viewModelScope.launch {
            settings.pairing.collect { p ->
                val changed = p != pairing
                pairing = p
                if (changed && p.isSet) connect()
            }
        }
    }

    // ── pairing ──────────────────────────────────────────────────────────────
    suspend fun tryPair(host: String, token: String): Result<SysInfo> = runCatching {
        verify(host, token)
    }.onSuccess {
        settings.save(host, token)
    }

    /**
     * Resolve a remote media file to a local URL any player can open.
     *
     * Returns null (and toasts) if the agent cannot be reached, so the caller
     * never fires an intent at a URL that will immediately fail.
     */
    fun streamUrl(name: String, onReady: (String, String) -> Unit) {
        val c = client ?: return
        val path = child(name)
        val mime = mediaMimeOf(name) ?: "application/octet-stream"
        viewModelScope.launch {
            runCatching { c.probe(path) }
                .onSuccess { (size, _) ->
                    if (size <= 0) {
                        toast = "$name is empty"
                        return@onSuccess
                    }
                    // The agent's guessed type is ignored in favour of ours:
                    // it reports octet-stream for containers like .mkv that
                    // Python's mimetypes does not know.
                    val url = media.urlFor(path, size, mime)
                    // Raised to the foreground before the player launches, or
                    // this process gets frozen the moment it loses focus.
                    MediaStreamService.start(getApplication(), name)
                    onReady(url, mime)
                }
                .onFailure { toast = "stream: ${it.message}" }
        }
    }

    fun toggleOpenInApp() {
        viewModelScope.launch { settings.setOpenInApp(!openInApp) }
    }

    /** Called when the activity resumes; cheap no-op if the link is healthy. */
    fun resumeIfDropped() {
        if (pairing.isSet && link == Link.OFFLINE) {
            if (client == null) connect() else reconnectSocket()
        }
    }

    fun toggleGridView() {
        viewModelScope.launch { settings.setGridView(!gridView) }
    }

    fun unpair() {
        disconnect()
        viewModelScope.launch { settings.clear() }
    }

    // ── connection ───────────────────────────────────────────────────────────
    fun connect() {
        if (!pairing.isSet) return
        disconnect()
        val c = AgentClient(pairing)
        client = c
        link = Link.CONNECTING
        channel = c.connect(
            onOpen = {
                link = Link.ONLINE
                refreshInfo()
                if (cwd.isBlank()) loadPlacesAndHome() else navigate(cwd, push = false)
            },
            onMessage = ::onSocketMessage,
            onClosed = { reason ->
                link = Link.OFFLINE
                ptyReady = false
                if (reason != null) toast = reason
            },
        )
        startPingLoop()
    }

    /** Re-open just the socket, keeping the existing client and ping loop. */
    private fun reconnectSocket() {
        val c = client ?: return
        channel?.close()
        link = Link.CONNECTING
        channel = c.connect(
            onOpen = {
                link = Link.ONLINE
                ptyReady = false
                if (cwd.isNotBlank()) navigate(cwd, push = false)
            },
            onMessage = ::onSocketMessage,
            onClosed = { link = Link.OFFLINE; ptyReady = false },
        )
    }

    fun disconnect() {
        pingJob?.cancel()
        channel?.close()
        channel = null
        link = Link.OFFLINE
        ptyReady = false
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            while (true) {
                val c = client ?: break
                runCatching { c.ping() }
                    .onSuccess {
                        pingMs = it
                        // The agent answers but the socket is gone -- Android
                        // reaps it whenever the app spends a while in the
                        // background, which is exactly what happens while an
                        // external player is in the foreground. Rebuild it
                        // rather than leaving the user on a dead "offline".
                        when (link) {
                            Link.CONNECTING -> link = Link.ONLINE
                            Link.OFFLINE -> reconnectSocket()
                            Link.ONLINE -> {}
                        }
                    }
                    .onFailure { pingMs = null }
                delay(5000)
            }
        }
    }

    private fun onSocketMessage(m: JsonObject) {
        when (m.get("t")?.asString) {
            "hello" -> {
                if (cwd.isBlank()) cwd = m.get("home")?.asString.orEmpty()
            }
            "pty.ready" -> ptyReady = true
            "pty.out" -> {
                term.feed(m.get("d")?.asString.orEmpty())
                termRevision = term.revision
            }
            "pty.exit" -> {
                ptyReady = false
                term.feed("\r\n[shell exited]\r\n")
                termRevision = term.revision
            }
            "err", "warn" -> toast = m.get("m")?.asString
        }
    }

    fun refreshInfo() = viewModelScope.launch {
        runCatching { client?.sysinfo() }.onSuccess { if (it != null) info = it }
    }

    // ── files ────────────────────────────────────────────────────────────────
    private fun loadPlacesAndHome() = viewModelScope.launch {
        runCatching { client?.places() }.onSuccess { places = it ?: emptyList() }
        navigate(info?.home ?: cwd.ifBlank { "~" })
    }

    fun navigate(path: String, push: Boolean = true) = viewModelScope.launch {
        val c = client ?: return@launch
        filesLoading = true
        filesError = null
        runCatching { c.list(path) }
            .onSuccess { listing ->
                cwd = listing.path
                entries.clear()
                entries.addAll(listing.entries)
                if (push && history.getOrNull(histIdx) != listing.path) {
                    while (history.size > histIdx + 1) history.removeAt(history.size - 1)
                    history.add(listing.path)
                    histIdx = history.size - 1
                }
            }
            .onFailure { filesError = it.message ?: "could not open $path" }
        filesLoading = false
    }

    fun back() {
        if (canBack) { histIdx--; navigate(history[histIdx], push = false) }
    }

    fun forward() {
        if (canForward) { histIdx++; navigate(history[histIdx], push = false) }
    }

    fun up() {
        val parent = cwd.trimEnd('/').substringBeforeLast('/', "")
        navigate(if (parent.isBlank()) "/" else parent)
    }

    fun child(name: String) = if (cwd == "/") "/$name" else "$cwd/$name"

    fun fsAction(label: String, block: suspend (AgentClient) -> Unit) =
        viewModelScope.launch {
            val c = client ?: return@launch
            runCatching { block(c) }
                .onSuccess { navigate(cwd, push = false) }
                .onFailure { toast = "$label: ${it.message}" }
        }

    // ── keys ─────────────────────────────────────────────────────────────────
    fun logKey(text: String) {
        keyLog.add(text)
        while (keyLog.size > 40) keyLog.removeAt(0)
    }

    /**
     * Sends a key with any latched modifiers folded in.
     *
     * The phone's modifier buttons latch rather than being held, so Ctrl+C is
     * two taps. The agent also holds the real modifier down, which matters for
     * anything that samples modifier state rather than reading a keysym.
     */
    fun sendKey(key: String) {
        val ch = channel ?: return
        val mods = heldModifiers.toList()
        if (mods.isEmpty()) {
            ch.key(key)
            logKey(key)
        } else {
            ch.combo((mods + key).joinToString("+"))
            logKey((mods + key).joinToString("+"))
            clearModifiers()
        }
    }

    fun sendCombo(combo: String) {
        channel?.combo(combo)
        logKey(combo)
    }

    fun toggleModifier(mod: String) {
        val ch = channel ?: return
        if (heldModifiers.contains(mod)) {
            heldModifiers.remove(mod)
            ch.modifier(mod, false)
        } else {
            heldModifiers.add(mod)
            ch.modifier(mod, true)
        }
    }

    private fun clearModifiers() {
        val ch = channel ?: return
        heldModifiers.forEach { ch.modifier(it, false) }
        heldModifiers.clear()
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        channel?.text(text)
        logKey("\"$text\"")
    }

    // ── shell ────────────────────────────────────────────────────────────────
    fun openPty(cols: Int, rows: Int) {
        channel?.ptyOpen(cols, rows, cwd.ifBlank { null })
    }

    fun runCommand(cmd: String) {
        channel?.ptyIn(cmd + "\n")
        if (cmd.isNotBlank()) {
            cmdHistory.remove(cmd)
            cmdHistory.add(0, cmd)
            while (cmdHistory.size > 50) cmdHistory.removeAt(cmdHistory.size - 1)
        }
    }

    fun clearTerm() {
        term.clear()
        termRevision = term.revision
    }

    override fun onCleared() {
        // Deliberately not stopping the stream server: the activity going away
        // while an external player is mid-file is the normal case, and the
        // foreground service is what decides when streaming is really over.
        disconnect()
        super.onCleared()
    }
}
