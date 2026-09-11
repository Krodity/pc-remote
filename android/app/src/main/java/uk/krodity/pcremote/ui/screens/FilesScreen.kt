package uk.krodity.pcremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.krodity.pcremote.data.FsEntry
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

                // ── breadcrumb ──────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
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
                LazyColumn(Modifier.fillMaxSize()) {

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
                                    onOpen = {
                                        if (entry.isDir) {
                                            vm.navigate(vm.child(entry.name))
                                        } else if (isTextLike(entry.name)) {
                                            scope.launch {
                                                runCatching { vm.client?.read(vm.child(entry.name)) }
                                                    .onSuccess { r ->
                                                        if (r != null) editor = entry.name to r.text
                                                    }
                                                    .onFailure { vm.toast = it.message }
                                            }
                                        } else {
                                            vm.toast = "${entry.name} is not a text file — use ⋮ → Open on PC"
                                        }
                                    },
                                    onMenu = { menuFor = if (menuFor == entry.name) null else entry.name },
                                )
                            }

                            if (menuFor == entry.name) {
                                ActionSheet(
                                    onRename = {
                                        renaming = entry.name; draft = entry.name; menuFor = null
                                    },
                                    onOpenOnPc = {
                                        menuFor = null
                                        vm.fsAction("open") { it.openOnPc(vm.child(entry.name)) }
                                    },
                                    onDelete = {
                                        menuFor = null
                                        vm.fsAction("delete") { it.delete(vm.child(entry.name)) }
                                        vm.toast = "${entry.name} → trash"
                                    },
                                )
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
    onRename: () -> Unit,
    onOpenOnPc: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(P.panel)) {
        listOf(
            Triple(Icons.Filled.DriveFileRenameOutline, "Rename", onRename),
            Triple(Icons.Filled.OpenInNew, "Open on PC", onOpenOnPc),
            Triple(Icons.Filled.Delete, "Trash", onDelete),
        ).forEach { (icon, label, action) ->
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
