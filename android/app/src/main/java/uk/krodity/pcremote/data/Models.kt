package uk.krodity.pcremote.data

import com.google.gson.annotations.SerializedName

/** One row in the file browser. Mirrors `stat_entry` in the agent. */
data class FsEntry(
    val name: String = "",
    @SerializedName("dir") val isDir: Boolean = false,
    val size: Long = 0,
    val mtime: Long = 0,
    val mode: String = "",
    val link: Boolean = false,
    val readable: Boolean = true,
    val error: String? = null,
)

data class FsListing(
    val path: String = "",
    val parent: String? = null,
    val entries: List<FsEntry> = emptyList(),
)

data class FsRead(
    val path: String = "",
    val text: String = "",
    val size: Long = 0,
    val binary: Boolean = false,
    val truncated: Boolean = false,
)

data class Place(val label: String = "", val path: String = "")

data class PlacesResponse(val places: List<Place> = emptyList())

data class MemInfo(val used: Long = 0, val total: Long = 0, val percent: Double = 0.0)

data class SysInfo(
    val host: String = "",
    val user: String = "",
    val shell: String = "",
    val home: String = "",
    val os: String = "",
    val kernel: String = "",
    val uptime: Long = 0,
    val load: List<Double> = emptyList(),
    val cpu: Double = 0.0,
    val mem: MemInfo? = null,
    val disk: MemInfo? = null,
    val uinput: String = "",
)

data class ExecResult(val code: Int = 0, val out: String = "", val err: String = "")

/** Connection state shown by the dot and latency readout in the top bar. */
enum class Link { OFFLINE, CONNECTING, ONLINE }
