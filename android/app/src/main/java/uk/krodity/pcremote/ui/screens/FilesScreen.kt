package uk.krodity.pcremote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import uk.krodity.pcremote.data.FsEntry
import uk.krodity.pcremote.data.OpenMode
import uk.krodity.pcremote.data.canThumb
import uk.krodity.pcremote.data.isImage
import uk.krodity.pcremote.data.isMedia
import uk.krodity.pcremote.data.viewableMimeOf
import uk.krodity.pcremote.ui.FieldStyle
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.fileColor
import uk.krodity.pcremote.ui.formatDate
import uk.krodity.pcremote.ui.formatSize
import uk.krodity.pcremote.ui.isTextLike
import uk.krodity.pcremote.ui.tapTarget
import uk.krodity.pcremote.ui.theme.Mono
import uk.krodity.pcremote.ui.theme.P

/**
 * The file browser, pointed at a real filesystem.
 *
 * Everything the mock mimed -- create, rename, delete, edit -- happens on the
 * PC here. The one deliberate difference is Delete: it moves to the freedesktop
 * trash rather than unlinking, because a mis-tap on a phone is much easier than
 * a mis-click on a desktop, and `~/.local/share/Trash` makes it recoverable.
 */
@Composable
fun FilesScreen(vm: RemoteViewModel) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }   // "file" | "dir"
    var renaming by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPlaces by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            // ── toolbar ─────────────────────────────────────────────────────
            Column(Modifier.fillMaxWidth().background(P.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", vm.canBack) { vm.back() }
                    ToolButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", vm.canForward) { vm.forward() }
                    ToolButton(Icons.Filled.ArrowUpward, "Up", vm.cwd != "/") { vm.up() }
                    ToolButton(Icons.Filled.Home, "Places", true) { showPlaces = !showPlaces }
                    Spacer(Modifier.weight(1f))
                    ToolButton(Icons.Filled.Refresh, "Refresh", true) { vm.navigate(vm.cwd, push = false) }
                    ToolButton(Icons.Filled.CreateNewFolder, "New folder", true) {
                        creating = "dir"; draft = ""; menuFor = null
                    }
                    ToolButton(Icons.Filled.Add, "New file", true) {
                        creating = "file"; draft = ""; menuFor = null
                    }
                }

                // ── breadcrumb + open-mode toggle ───────────────────────────
                // The toggle lives here rather than in the tool row above: eight
                // 40dp controls do not fit across a ~393dp-wide phone, and this
                // row has spare width once the crumbs are allowed to scroll.
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val parts = vm.cwd.trim('/').split('/').filter { it.isNotBlank() }
                    Text(
                        "/",
                        color = if (parts.isEmpty()) P.text else P.sub,
                        fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier
                            .tapTarget { vm.navigate("/") }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    parts.forEachIndexed { i, part ->
                        if (i > 0) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                tint = P.mute, modifier = Modifier.size(10.dp),
                            )
                        }
                        Text(
                            part,
                            color = if (i == parts.lastIndex) P.text else P.sub,
                            fontFamily = Mono, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier
                                .tapTarget { vm.navigate("/" + parts.take(i + 1).joinToString("/")) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                    Spacer(Modifier.width(8.dp))
                    OpenModeToggle(vm.openMode) { vm.cycleOpenMode() }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (vm.gridView) P.glow else P.panel)
                            .border(
                                1.dp,
                                if (vm.gridView) P.accent else P.border,
                                RoundedCornerShape(8.dp),
                            )
                            .tapTarget { vm.toggleGridView() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (vm.gridView) Icons.AutoMirrored.Filled.ViewList
                            else Icons.Filled.GridView,
                            "Switch between list and thumbnails",
                            tint = if (vm.gridView) P.accent else P.sub,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))

            // ── places drawer ───────────────────────────────────────────────
            if (showPlaces) {
                Column(Modifier.fillMaxWidth().background(P.panel)) {
                    vm.places.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { place ->
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .tapTarget {
                                            vm.navigate(place.path); showPlaces = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Folder, null, tint = P.amber,
                                        modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(place.label, color = P.text, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
            }

            // ── listing ─────────────────────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val openEntry: (FsEntry) -> Unit = { entry ->
                    when {
                        entry.isDir -> vm.navigate(vm.child(entry.name))
                        vm.openMode == OpenMode.PC -> vm.fsAction("open") {
                            it.openOnPc(vm.child(entry.name))
                        }
                        // "Open with" covers every type the phone has a
                        // handler for; text with no handler still has ours.
                        vm.openMode == OpenMode.EXTERNAL ->
                            if (viewableMimeOf(entry.name) != null) {
                                openWithApp(vm, ctx, entry.name)
                            } else {
                                openHere(vm, scope, entry.name) { editor = entry.name to it }
                            }
                        // In-app: images get the viewer, text the editor, and
                        // video a player, since there is no point writing one.
                        isImage(entry.name) -> vm.viewingImage = entry.name
                        isMedia(entry.name) -> openWithApp(vm, ctx, entry.name)
                        else -> openHere(vm, scope, entry.name) {
                            editor = entry.name to it
                        }
                    }
                }

                if (vm.gridView) {
                    GridListing(vm, openEntry) { menuFor = if (menuFor == it) null else it }
                    vm.entries.firstOrNull { it.name == menuFor }?.let { entry ->
                        Column(Modifier.align(Alignment.BottomCenter)) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
                            EntryActions(vm, scope, ctx, entry,
                                onDismiss = { menuFor = null },
                                onRename = { renaming = entry.name; draft = entry.name },
                                onEdit = { editor = entry.name to it })
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize()) {

                    if (creating != null) {
                        item {
                            InlineNameRow(
                                icon = if (creating == "dir") Icons.Filled.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                tint = if (creating == "dir") P.amber else P.sub,
                                value = draft,
                                placeholder = if (creating == "dir") "Folder name" else "File name",
                                onValue = { draft = it },
                                onConfirm = {
                                    val name = draft.trim()
                                    if (name.isNotEmpty()) {
                                        val target = vm.child(name)
                                        if (creating == "dir") vm.fsAction("mkdir") { it.mkdir(target) }
                                        else vm.fsAction("create") { it.create(target) }
                                    }
                                    creating = null; draft = ""
                                },
                                onCancel = { creating = null; draft = "" },
                            )
                        }
                    }

                    items(vm.entries.size, key = { vm.entries[it].name }) { idx ->
                        val entry = vm.entries[idx]
                        Column {
                            if (renaming == entry.name) {
                                InlineNameRow(
                                    icon = iconFor(entry),
                                    tint = fileColor(entry.name, entry.isDir),
                                    value = draft,
                                    placeholder = entry.name,
                                    onValue = { draft = it },
                                    onConfirm = {
                                        val name = draft.trim()
                                        if (name.isNotEmpty() && name != entry.name) {
                                            vm.fsAction("rename") {
                                                it.rename(vm.child(entry.name), name)
                                            }
                                        }
                                        renaming = null; draft = ""
                                    },
                                    onCancel = { renaming = null; draft = "" },
                                )
                            } else {
                                FileRow(
                                    entry = entry,
                                    onOpen = { openEntry(entry) },
                                    onMenu = { menuFor = if (menuFor == entry.name) null else entry.name },
                                )
                            }

                            if (menuFor == entry.name) {
                                EntryActions(vm, scope, ctx, entry,
                                    onDismiss = { menuFor = null },
                                    onRename = { renaming = entry.name; draft = entry.name },
                                    onEdit = { editor = entry.name to it })
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
                        }
                    }

                    if (vm.entries.isEmpty() && creating == null && !vm.filesLoading) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 60.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = P.mute,
                                    modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(vm.filesError ?: "Empty directory", color = P.sub, fontSize = 14.sp)
                                if (vm.filesError == null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Tap + to create a file", color = P.mute, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                if (vm.filesLoading) {
                    CircularProgressIndicator(
                        color = P.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.TopCenter)
                            .padding(top = 12.dp).size(20.dp),
                    )
                }
            }
        }

        // ── text editor ─────────────────────────────────────────────────────
        editor?.let { (name, content) ->
            TextEditor(
                path = vm.child(name),
                initial = content,
                onCancel = { editor = null },
                onSave = { text ->
                    vm.fsAction("save") { it.write(vm.child(name), text) }
                    editor = null
                    vm.toast = "saved $name"
                },
            )
        }
    }
}

/**
 * The per-file action row, wired to the browser's state.
 *
 * Extracted so the grid and the list share it rather than only the list having
 * one -- the actions are no longer a shortcut for a tap now that a picture can
 * go three different places.
 */
@Composable
private fun EntryActions(
    vm: RemoteViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    ctx: android.content.Context,
    entry: FsEntry,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEdit: (String) -> Unit,
) {
    ActionSheet(
        canEditHere = !entry.isDir && !isMedia(entry.name) && !isImage(entry.name),
        canView = isImage(entry.name),
        canOpenWith = viewableMimeOf(entry.name) != null,
        isVideo = isMedia(entry.name),
        onView = { onDismiss(); vm.viewingImage = entry.name },
        onOpenWith = { onDismiss(); openWithApp(vm, ctx, entry.name) },
        onRename = { onDismiss(); onRename() },
        onOpenOnPc = {
            onDismiss()
            vm.fsAction("open") { it.openOnPc(vm.child(entry.name)) }
        },
        onEditHere = {
            onDismiss()
            openHere(vm, scope, entry.name, onEdit)
        },
        onDelete = {
            onDismiss()
            vm.fsAction("delete") { it.delete(vm.child(entry.name)) }
            vm.toast = "${entry.name} → trash"
        },
    )
}

/**
 * Hand a remote file to whatever app on this phone the user prefers.
 *
 * The URL points at the app's own loopback stream server, not at the agent: a
 * third-party app cannot authenticate, and putting the bearer token in a URL
 * would hand the credential to an app we do not control.
 *
 * Fired as a bare ACTION_VIEW rather than through `createChooser`. A forced
 * chooser cannot be dismissed with "Always", so it makes setting a default
 * impossible -- with a plain intent Android shows its own "Open with" dialog
 * offering *Just once* / *Always* and then remembers the choice. That is the
 * whole mechanism by which a default gallery or player gets picked, for stills
 * exactly as for video. The chooser is kept only as the fallback for when
 * nothing claims the type at all.
 */
internal fun openWithApp(vm: RemoteViewModel, ctx: android.content.Context, name: String) {
    val fire: (Uri, String, Boolean) -> Unit = { uri, mime, grant ->
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (grant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", name)
        }
        val ok = runCatching { ctx.startActivity(view) }.isSuccess
        if (!ok) {
            runCatching { ctx.startActivity(Intent.createChooser(view, "Open ${'$'}name")) }
                .onFailure { vm.toast = "no app on this phone can open ${'$'}name" }
        }
    }

    // A still is copied and handed over as content://; a film is streamed from
    // the loopback server. See SharedFiles for why they cannot share a route.
    if (isImage(name) && vm.canCopyLocally(name)) {
        vm.shareUri(name) { uri, mime -> fire(uri, mime, true) }
    } else {
        vm.streamUrl(name) { url, mime -> fire(Uri.parse(url), mime, false) }
    }
}

/**
 * Pull a file's text down and hand it to the in-app editor.
 *
 * Only text is offered here — the phone has no viewer for anything else, and
 * the PC already has one for everything.
 */
private fun openHere(
    vm: RemoteViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    name: String,
    onLoaded: (String) -> Unit,
) {
    if (!isTextLike(name)) {
        vm.toast = "$name is not text — open it on the PC instead"
        return
    }
    scope.launch {
        runCatching { vm.client?.read(vm.child(name)) }
            .onSuccess { r -> if (r != null) onLoaded(r.text) }
            .onFailure { vm.toast = it.message }
    }
}

/**
 * Where a tap sends a file: the PC, this app, or another app on the phone.
 *
 * A labelled pill rather than a bare icon — "opens somewhere else entirely" is
 * too consequential a mode to leave the user guessing at from a glyph. It
 * cycles rather than expanding into a menu because it is three states in a row
 * that has room for a pill and nothing more.
 *
 * "Open with…" is the mode that lets a default be set: it fires a plain
 * ACTION_VIEW, so Android's own dialog offers *Always* and remembers the
 * gallery or player chosen for that type from then on.
 */
@Composable
private fun OpenModeToggle(mode: OpenMode, onToggle: () -> Unit) {
    val (icon, label) = when (mode) {
        OpenMode.PC -> Icons.Filled.DesktopWindows to "Open on PC"
        OpenMode.APP -> Icons.Filled.PhoneAndroid to "Open in app"
        OpenMode.EXTERNAL -> Icons.Filled.OpenInNew to "Open with…"
    }
    val on = mode != OpenMode.PC
    val tint = if (on) P.accent else P.sub
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) P.glow else P.panel)
            .border(1.dp, if (on) P.accent else P.border, RoundedCornerShape(20.dp))
            .tapTarget(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            "Tap to cycle where files open: the PC, this app, or another app",
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(label, color = tint, fontSize = 11.sp)
    }
}

/**
 * Thumbnail grid.
 *
 * Adaptive columns rather than a fixed count so it stays sensible on the razr's
 * inner and outer screens, which differ enormously in width.
 */
@Composable
private fun GridListing(
    vm: RemoteViewModel,
    onOpen: (FsEntry) -> Unit,
    onMenu: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(vm.entries.size, key = { vm.entries[it].name }) { idx ->
            val entry = vm.entries[idx]
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(P.surface)
                    .border(1.dp, P.border, RoundedCornerShape(10.dp))
                    .tapTarget { onOpen(entry) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(P.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Thumb(vm, entry)
                    if (isMedia(entry.name)) {
                        // A play badge distinguishes a video from a still at a
                        // glance -- both render as a single frame otherwise.
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(5.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(P.bg.copy(alpha = 0.72f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = P.accent,
                                modifier = Modifier.size(13.dp))
                        }
                    }
                }
                Row(
                    // Fixed caption height: letting it size to 1 or 2 wrapped
                    // lines made every row of the grid a different height.
                    Modifier.fillMaxWidth().height(42.dp)
                        .padding(start = 7.dp, end = 2.dp, top = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        entry.name,
                        color = P.text, fontSize = 11.sp, lineHeight = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(22.dp).tapTarget { onMenu(entry.name) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MoreVert, "Actions", tint = P.sub,
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** A preview if the agent can render one, otherwise the type icon. */
@Composable
private fun Thumb(vm: RemoteViewModel, entry: FsEntry) {
    val path = vm.child(entry.name)
    val bitmap by produceState<ImageBitmap?>(null, path, entry.mtime) {
        value = if (entry.isDir || !canThumb(entry.name)) null
        else vm.client?.let { vm.thumbs.load(it, path, entry.mtime) }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Icon(
            iconFor(entry), null,
            tint = fileColor(entry.name, entry.isDir),
            modifier = Modifier.size(34.dp),
        )
    }
}

private fun iconFor(e: FsEntry): ImageVector = when {
    e.isDir -> Icons.Filled.Folder
    isTextLike(e.name) -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, P.border, RoundedCornerShape(8.dp))
            .tapTarget(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) P.sub else P.mute, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FileRow(entry: FsEntry, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tapTarget(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(entry), null,
            tint = fileColor(entry.name, entry.isDir),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    color = if (entry.readable) P.text else P.mute,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.link) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Link, "symlink", tint = P.accentD,
                        modifier = Modifier.size(12.dp))
                }
            }
            Text(
                buildString {
                    append(formatDate(entry.mtime))
                    if (!entry.isDir) append(" · ${formatSize(entry.size)}")
                    if (entry.mode.isNotBlank()) append(" · ${entry.mode}")
                },
                color = P.sub, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (entry.isDir) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = P.mute,
                modifier = Modifier.size(16.dp))
        }
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).tapTarget(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MoreVert, "Actions", tint = P.sub, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ActionSheet(
    canEditHere: Boolean,
    canView: Boolean,
    canOpenWith: Boolean,
    isVideo: Boolean,
    onView: () -> Unit,
    onOpenWith: () -> Unit,
    onRename: () -> Unit,
    onOpenOnPc: () -> Unit,
    onEditHere: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(P.panel)) {
        buildList {
            add(Triple(Icons.Filled.DriveFileRenameOutline, "Rename", onRename))
            add(Triple(Icons.Filled.DesktopWindows, "On PC", onOpenOnPc))
            // Every destination stays reachable whatever the toggle is set to.
            if (canView) add(Triple(Icons.Filled.Image, "View here", onView))
            if (canOpenWith) {
                // Same intent either way; the verb is what the user expects to
                // happen to the file, not what the code does with it.
                if (isVideo) add(Triple(Icons.Filled.PlayArrow, "Play", onOpenWith))
                else add(Triple(Icons.Filled.OpenInNew, "Open with", onOpenWith))
            }
            if (canEditHere) add(Triple(Icons.Filled.Edit, "Edit here", onEditHere))
            add(Triple(Icons.Filled.Delete, "Trash", onDelete))
        }.forEach { (icon, label, action) ->
            val danger = label == "Trash"
            Column(
                Modifier.weight(1f).tapTarget(onClick = action).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, label, tint = if (danger) P.red else P.sub,
                    modifier = Modifier.size(15.dp))
                Text(label, color = if (danger) P.red else P.sub, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun InlineNameRow(
    icon: ImageVector,
    tint: Color,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(P.glow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        FieldStyle(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(P.accent)
                .tapTarget(onClick = onConfirm)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("OK", color = Color.Black, fontSize = 13.sp)
        }
        Box(
            Modifier.size(36.dp).tapTarget(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, "Cancel", tint = P.sub, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TextEditor(
    path: String,
    initial: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(path) { mutableStateOf(initial) }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        Row(
            Modifier.fillMaxWidth().background(P.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Description, null, tint = P.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                path, color = P.text, fontFamily = Mono, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(32.dp).tapTarget(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "Close", tint = P.sub, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))

        FieldStyle(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            mono = true,
            singleLine = false,
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(
            Modifier.fillMaxWidth().background(P.surface).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, P.border, RoundedCornerShape(8.dp))
                    .tapTarget(onClick = onCancel).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", color = P.sub, fontSize = 14.sp) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(P.accent)
                    .tapTarget { onSave(text) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Save", color = Color.Black, fontSize = 14.sp) }
        }
    }
}
