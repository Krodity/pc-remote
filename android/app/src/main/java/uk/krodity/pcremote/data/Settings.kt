package uk.krodity.pcremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("pc-remote")

/**
 * What a tap on a file does.
 *
 * Three destinations, not two, because "on the phone" turned out to be two
 * different things: the app's own viewer and editor, or whatever gallery or
 * player the user already prefers. [EXTERNAL] exists so that choice can be
 * made once, in Android's own "Open with" dialog, and then remembered.
 */
enum class OpenMode {
    /** `xdg-open` on the PC — the file never leaves the desk. */
    PC,

    /** This app: built-in image viewer, text editor, external player for video. */
    APP,

    /** Hand it to another app on this phone via a plain ACTION_VIEW. */
    EXTERNAL,
    ;

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Where the agent lives and the token that proves we were paired with it. */
data class Pairing(val host: String = "", val token: String = "") {
    val isSet get() = host.isNotBlank() && token.isNotBlank()

    /**
     * Normalise whatever the user typed into "host:port".
     *
     * Accepts a MagicDNS name ("my-pc"), an IPv4 or bare IPv6 literal, any of
     * those with an explicit port, and a full URL pasted from the pair page.
     * Bare IPv6 has to be bracketed before a port can be appended, otherwise
     * the last colon of the address reads as the port separator -- this box
     * has a v6 tailnet address, so that case is real, not theoretical.
     */
    private val hostPort: String
        get() {
            val h = host.trim()
                .removePrefix("http://").removePrefix("https://")
                .removePrefix("ws://").removePrefix("wss://")
                .substringBefore('/')
            if (h.isEmpty()) return "localhost:$HTTP_PORT"
            if (h.startsWith("[")) {                       // [v6] or [v6]:port
                return if (h.substringAfterLast(']').startsWith(':')) h
                else "$h:$HTTP_PORT"
            }
            if (h.count { it == ':' } > 1) return "[$h]:$HTTP_PORT"  // bare v6
            return if (h.contains(':')) h else "$h:$HTTP_PORT"
        }

    val httpBase get() = "http://$hostPort"

    /** The WS port is the HTTP port + 1, as the agent lays them out. */
    val wsBase: String
        get() {
            val hp = hostPort
            val i = hp.lastIndexOf(':')
            val p = hp.substring(i + 1).toIntOrNull() ?: HTTP_PORT
            return "ws://${hp.substring(0, i)}:${p + 1}"
        }

    companion object {
        const val HTTP_PORT = 8778
    }
}

class SettingsRepo(private val ctx: Context) {
    private val hostKey = stringPreferencesKey("host")
    private val tokenKey = stringPreferencesKey("token")
    private val openInAppKey = booleanPreferencesKey("open_in_app")   // legacy; migrated
    private val openModeKey = stringPreferencesKey("open_mode")
    private val gridViewKey = booleanPreferencesKey("grid_view")

    val pairing: Flow<Pairing> = ctx.store.data.map {
        Pairing(it[hostKey] ?: "", it[tokenKey] ?: "")
    }

    /**
     * Where a tapped file opens.
     *
     * Defaults to the PC: `xdg-open` there knows what to do with every type,
     * and the phone does not. An install made before the setting had three
     * values is read off the old boolean, so nobody's choice is reset by the
     * upgrade.
     */
    val openMode: Flow<OpenMode> = ctx.store.data.map { prefs ->
        prefs[openModeKey]?.let { name ->
            OpenMode.entries.firstOrNull { it.name == name }
        } ?: if (prefs[openInAppKey] == true) OpenMode.APP else OpenMode.PC
    }

    suspend fun save(host: String, token: String) {
        ctx.store.edit {
            it[hostKey] = host.trim()
            it[tokenKey] = token.trim()
        }
    }

    /** true = thumbnail grid, false = detail list. */
    val gridView: Flow<Boolean> = ctx.store.data.map { it[gridViewKey] ?: false }

    suspend fun setGridView(value: Boolean) {
        ctx.store.edit { it[gridViewKey] = value }
    }

    suspend fun setOpenMode(value: OpenMode) {
        ctx.store.edit { it[openModeKey] = value.name }
    }

    suspend fun clear() {
        ctx.store.edit { it.clear() }
    }
}
