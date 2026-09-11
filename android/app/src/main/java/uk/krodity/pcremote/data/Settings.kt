package uk.krodity.pcremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("pc-remote")

/** Where the agent lives and the token that proves we were paired with it. */
data class Pairing(val host: String = "", val token: String = "") {
    val isSet get() = host.isNotBlank() && token.isNotBlank()

    /**
     * Normalise whatever the user typed into "host:port".
     *
     * Accepts a MagicDNS name ("aepc"), an IPv4 or bare IPv6 literal, any of
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
    private val openInAppKey = booleanPreferencesKey("open_in_app")
    private val gridViewKey = booleanPreferencesKey("grid_view")

    val pairing: Flow<Pairing> = ctx.store.data.map {
        Pairing(it[hostKey] ?: "", it[tokenKey] ?: "")
    }

    /**
     * Where a tapped file opens.
     *
     * Defaults to false — i.e. on the PC. The phone's editor only handles text,
     * and the desktop already knows what to do with every other type, so
     * handing the file to `xdg-open` is right far more often than not.
     */
    val openInApp: Flow<Boolean> = ctx.store.data.map { it[openInAppKey] ?: false }

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

    suspend fun setOpenInApp(value: Boolean) {
        ctx.store.edit { it[openInAppKey] = value }
    }

    suspend fun clear() {
        ctx.store.edit { it.clear() }
    }
}
