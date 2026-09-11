package uk.krodity.pcremote.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.krodity.pcremote.ui.FieldStyle
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.tapTarget
import uk.krodity.pcremote.ui.theme.Mono
import uk.krodity.pcremote.ui.theme.P

/**
 * A real terminal, not a command box.
 *
 * The mock faked a prompt and matched commands against a lookup table. This is
 * attached to an actual PTY on the PC, so `top`, `vim` and `sudo`'s password
 * prompt all behave -- which is also why the control strip exists: Ctrl-C,
 * Tab-completion and arrow-key history need somewhere to live when the phone's
 * soft keyboard has none of those keys.
 */
private val CONTROL_KEYS = listOf(
    "^C" to "\u0003",        // SIGINT
    "^D" to "\u0004",        // EOF
    "^Z" to "\u001A",        // suspend
    "^L" to "\u000C",        // clear
    "TAB" to "\t",           // completion
    "ESC" to "\u001B",
    "\u2191" to "\u001B[A",  // history back
    "\u2193" to "\u001B[B",
    "\u2190" to "\u001B[D",
    "\u2192" to "\u001B[C",
)

@Composable
fun ShellScreen(vm: RemoteViewModel) {
    var input by remember { mutableStateOf("") }
    var histIdx by remember { mutableStateOf(-1) }
    val scroll = rememberScrollState()

    // Columns are estimated from the screen width at the terminal's font size,
    // so line wrapping on the PC matches what is rendered here. Getting this
    // wrong is what makes remote shells wrap in the wrong place.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val cols = ((widthDp - 28) / 7).coerceIn(40, 200)
    val rows = 40

    LaunchedEffect(vm.link) {
        if (vm.link == uk.krodity.pcremote.data.Link.ONLINE && !vm.ptyReady) {
            vm.openPty(cols, rows)
        }
    }

    LaunchedEffect(vm.termRevision) {
        scroll.scrollTo(scroll.maxValue)
    }

    Column(Modifier.fillMaxSize().background(P.term)) {

        // ── window chrome ───────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(P.nav)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF22C55E)).forEach {
                Box(Modifier.size(11.dp).clip(CircleShape).background(it))
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(2.dp))
            Text(
                "${vm.info?.shell?.substringAfterLast('/') ?: "shell"} — ${vm.cwd}",
                color = P.sub, fontSize = 11.sp, fontFamily = Mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "clear",
                color = P.mute, fontSize = 11.sp, fontFamily = Mono,
                modifier = Modifier
                    .tapTarget { vm.clearTerm() }
                    .padding(horizontal = 6.dp),
            )
            Text(
                "restart",
                color = P.mute, fontSize = 11.sp, fontFamily = Mono,
                modifier = Modifier
                    .tapTarget { vm.clearTerm(); vm.openPty(cols, rows) }
                    .padding(start = 6.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))

        // ── output ──────────────────────────────────────────────────────────
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (vm.termRevision == 0) {
                Text(
                    if (vm.link == uk.krodity.pcremote.data.Link.ONLINE)
                        "starting shell…"
                    else "offline — tap ⟳ in the title bar to reconnect",
                    color = P.sub, fontFamily = Mono, fontSize = 13.sp,
                )
            } else {
                // Keyed on the revision so Compose redraws when the buffer
                // mutates in place rather than being replaced.
                key(vm.termRevision) {
                    Text(
                        vm.term.render(),
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = P.text,
                    )
                }
            }
        }

        // ── control strip ───────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(
            Modifier
                .fillMaxWidth()
                .background(P.panel)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CONTROL_KEYS.forEach { (label, seq) ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(P.surface)
                        .tapTarget { vm.channel?.ptyIn(seq) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(label, color = P.sub, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        }

        // ── input ───────────────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(
            Modifier
                .fillMaxWidth()
                .background(P.term)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$",
                color = P.accent, fontFamily = Mono, fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
            )
            FieldStyle(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = "enter command…",
                mono = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    vm.runCommand(input); input = ""; histIdx = -1
                }),
            )
            Spacer(Modifier.width(8.dp))

            // History is reachable without a hardware Up key; the arrow in the
            // control strip sends a real escape sequence to the shell instead.
            if (vm.cmdHistory.isNotEmpty()) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(P.surface)
                        .tapTarget {
                            histIdx = (histIdx + 1).coerceAtMost(vm.cmdHistory.size - 1)
                            input = vm.cmdHistory[histIdx]
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↑", color = P.sub, fontFamily = Mono, fontSize = 16.sp)
                }
                Spacer(Modifier.width(6.dp))
            }

            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(P.accent)
                    .tapTarget { vm.runCommand(input); input = ""; histIdx = -1 },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = Color.Black,
                    modifier = Modifier.size(17.dp))
            }
        }
    }
}
