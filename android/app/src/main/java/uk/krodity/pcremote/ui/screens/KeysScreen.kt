package uk.krodity.pcremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.krodity.pcremote.ui.FieldStyle
import uk.krodity.pcremote.ui.KeyTile
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.ComboTile
import uk.krodity.pcremote.ui.SectionLabel
import uk.krodity.pcremote.ui.tapTarget
import uk.krodity.pcremote.ui.theme.Mono
import uk.krodity.pcremote.ui.theme.P

/** Modifiers latch on tap; the next key folds them in and clears them. */
private val MODIFIERS = listOf(
    "ctrl" to P.purple, "alt" to P.purple, "shift" to P.purple,
    "super" to P.accent, "altgr" to P.sub, "caps" to P.sub,
)

private val SPECIALS = listOf(
    "esc" to P.red, "tab" to P.sub, "del" to P.red, "backspace" to P.red,
    "home" to P.sub, "end" to P.sub, "pgup" to P.sub, "pgdn" to P.sub,
    "insert" to P.sub, "menu" to P.sub, "printscreen" to P.sub, "pause" to P.sub,
)

private val FN_KEYS = (1..12).map { "f$it" }

/**
 * Combos worth a dedicated button on this machine.
 *
 * These are read off the live Hyprland config rather than carried over from the
 * mock's Windows list -- Super+Q closes a window here, Super+Space is Ulauncher,
 * and Alt+F4 does nothing at all.
 */
private val COMBOS = listOf(
    "super+enter" to "Terminal",
    "super+space" to "Launcher",
    "super+q" to "Close window",
    "super+f" to "Fullscreen",
    "super+shift+enter" to "Browser",
    "super+shift+f" to "Files",
    "ctrl+c" to "Copy",
    "ctrl+v" to "Paste",
    "ctrl+x" to "Cut",
    "ctrl+z" to "Undo",
    "ctrl+a" to "Select all",
    "ctrl+s" to "Save",
    "ctrl+alt+delete" to "Session menu",
    "super+shift+s" to "Screenshot",
)

private val MEDIA = listOf(
    "playpause" to "Play/Pause", "previoussong" to "Prev", "nextsong" to "Next",
    "volumedown" to "Vol −", "volumeup" to "Vol +", "mute" to "Mute",
)

@Composable
fun KeysScreen(vm: RemoteViewModel) {
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {

        // ── type-a-string bar ───────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(P.surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldStyle(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = "Type text to send…",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendText(draft); draft = ""
                }),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(P.accent)
                    .tapTarget { vm.sendText(draft); draft = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send text", tint = Color.Black,
                    modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Section("MODIFIERS") {
                Grid(MODIFIERS.map { it.first }, columns = 3) { key ->
                    val colour = MODIFIERS.first { it.first == key }.second
                    KeyTile(
                        label = key,
                        modifier = Modifier.weight(1f),
                        active = vm.heldModifiers.contains(key),
                        accent = colour,
                    ) { vm.toggleModifier(key) }
                }
            }

            Section("SPECIAL KEYS") {
                Grid(SPECIALS.map { it.first }, columns = 4) { key ->
                    KeyTile(
                        label = key,
                        modifier = Modifier.weight(1f),
                        accent = SPECIALS.first { it.first == key }.second,
                        fontSize = 11,
                        vertical = 11.dp,
                    ) { vm.sendKey(key) }
                }
            }

            Section("NAVIGATE") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DPad(vm)
                }
            }

            Section("FUNCTION KEYS") {
                Grid(FN_KEYS, columns = 6, gap = 6.dp) { key ->
                    KeyTile(
                        label = key.uppercase(),
                        modifier = Modifier.weight(1f),
                        fontSize = 11,
                        vertical = 10.dp,
                    ) { vm.sendKey(key) }
                }
            }

            Section("MEDIA") {
                Grid(MEDIA.map { it.first }, columns = 3) { key ->
                    KeyTile(
                        label = MEDIA.first { it.first == key }.second,
                        modifier = Modifier.weight(1f),
                        mono = false,
                        fontSize = 12,
                        vertical = 11.dp,
                    ) { vm.sendKey(key) }
                }
            }

            Section("QUICK COMBOS") {
                Grid(COMBOS.map { it.first }, columns = 2) { combo ->
                    ComboTile(
                        combo = combo.split("+").joinToString("+") { it.replaceFirstChar(Char::uppercase) },
                        label = COMBOS.first { it.first == combo }.second,
                        modifier = Modifier.weight(1f),
                    ) { vm.sendCombo(combo) }
                }
            }

            if (vm.keyLog.isNotEmpty()) {
                Section("SENT") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(P.surface)
                            .border(1.dp, P.border, RoundedCornerShape(8.dp))
                    ) {
                        LazyColumn(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            reverseLayout = true,
                        ) {
                            items(vm.keyLog.size) { i ->
                                Text(
                                    vm.keyLog[vm.keyLog.size - 1 - i],
                                    fontFamily = Mono, fontSize = 11.sp, color = P.sub,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Latched modifiers are easy to forget about; keep them visible and
        // one tap from being cleared.
        if (vm.heldModifiers.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(P.panel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vm.heldModifiers.joinToString("+") + "+…",
                    color = P.accent, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "clear",
                    color = P.sub, fontSize = 12.sp,
                    modifier = Modifier.tapTarget {
                        vm.heldModifiers.toList().forEach { vm.toggleModifier(it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 8.dp))
        content()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
}

/** A fixed-column grid built from Rows -- it lives inside a scrolling Column. */
@Composable
private fun Grid(
    items: List<String>,
    columns: Int,
    gap: androidx.compose.ui.unit.Dp = 8.dp,
    cell: @Composable androidx.compose.foundation.layout.RowScope.(String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { cell(it) }
                // Keep the last row's cells the same width as every other row.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The circular arrow cluster from the mock, with Enter at its centre. */
@Composable
private fun DPad(vm: RemoteViewModel) {
    var pressed by remember { mutableStateOf<String?>(null) }
    val cell = 58.dp

    @Composable
    fun Arrow(key: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Box(
            Modifier
                .size(cell)
                .background(if (pressed == key) P.glow else Color.Transparent)
                .tapTarget { pressed = key; vm.sendKey(key) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, key,
                tint = if (pressed == key) P.accent else P.text,
                modifier = Modifier.size(24.dp),
            )
        }
    }

    Box(
        Modifier
            .size(cell * 3)
            .clip(CircleShape)
            .background(P.panel)
            .border(1.dp, P.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Row {
                Spacer(Modifier.size(cell))
                Arrow("up", Icons.Filled.KeyboardArrowUp)
                Spacer(Modifier.size(cell))
            }
            Row {
                Arrow("left", Icons.AutoMirrored.Filled.KeyboardArrowLeft)
                Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (pressed == "enter") P.glow else P.surface)
                            .border(
                                1.5.dp,
                                if (pressed == "enter") P.accent else P.accentD,
                                CircleShape,
                            )
                            .tapTarget { pressed = "enter"; vm.sendKey("enter") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardReturn, "enter",
                            tint = if (pressed == "enter") P.accent else P.sub,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Arrow("right", Icons.AutoMirrored.Filled.KeyboardArrowRight)
            }
            Row {
                Spacer(Modifier.size(cell))
                Arrow("down", Icons.Filled.KeyboardArrowDown)
                Spacer(Modifier.size(cell))
            }
        }
    }
}
