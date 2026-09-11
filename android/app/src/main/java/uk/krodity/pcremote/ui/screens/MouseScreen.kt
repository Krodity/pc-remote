package uk.krodity.pcremote.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.tapTarget
import uk.krodity.pcremote.ui.theme.Mono
import uk.krodity.pcremote.ui.theme.P
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The trackpad.
 *
 * The mock tracked an absolute cursor position and drew coordinates, which only
 * worked because it was pretending. A real remote has no idea where the PC's
 * pointer is -- and asking would cost a round trip per frame. So this sends
 * *relative* deltas, exactly as a physical mouse does, and the readout shows
 * what it actually knows: how far the finger moved and how fast.
 *
 * Gestures follow laptop-trackpad convention rather than inventing new ones:
 * one finger drags, one tap is a left click, two fingers scroll, two-finger tap
 * is a right click, and a drag that begins with a double-tap holds the button
 * down for select-and-drag.
 */
@Composable
fun MouseScreen(vm: RemoteViewModel) {
    var sensitivity by remember { mutableFloatStateOf(2f) }
    var speed by remember { mutableFloatStateOf(0f) }
    var touching by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<String?>(null) }
    var dragLatched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Decay the speed readout so it falls back to rest instead of freezing on
    // the last value the finger happened to produce.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(80)
            if (!touching && speed > 0f) speed = (speed - 60f).coerceAtLeast(0f)
        }
    }

    fun flash(which: String) {
        pressed = which
        scope.launch { delay(160); if (pressed == which) pressed = null }
    }

    Column(Modifier.fillMaxSize()) {

        // ── status + sensitivity ────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(P.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (dragLatched) "drag held" else "${speed.roundToInt()} px/s",
                    color = if (dragLatched) P.amber else P.accent,
                    fontFamily = Mono, fontSize = 14.sp,
                )
                Text(
                    "Sensitivity ${"%.1f".format(sensitivity)}×",
                    color = P.sub, fontSize = 12.sp,
                )
            }
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0.5f..6f,
                steps = 10,
                colors = SliderDefaults.colors(
                    thumbColor = P.accent,
                    activeTrackColor = P.accent,
                    inactiveTrackColor = P.mute,
                ),
                modifier = Modifier.height(28.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))

        // ── the pad ─────────────────────────────────────────────────────────
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(P.bg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        touching = true

                        val downAt = System.currentTimeMillis()
                        var moved = 0f
                        var pointers = 1
                        // Accumulates scroll travel so one wheel notch is sent
                        // per ~40px of two-finger movement.
                        var scrollAcc = 0f
                        var lastMove = downAt

                        // A drag that starts within the double-tap window holds
                        // the left button: tap-tap-drag to select text.
                        val holdDrag = System.currentTimeMillis() - lastTapAt < 300
                        if (holdDrag) {
                            vm.channel?.button("left", true)
                            dragLatched = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val active = event.changes.filter { it.pressed }
                            pointers = maxOf(pointers, active.size)
                            if (active.isEmpty()) break

                            if (active.size >= 2) {
                                // Two fingers: vertical scroll, natural direction.
                                val dy = active.map { it.positionChange().y }.average().toFloat()
                                scrollAcc += dy
                                while (abs(scrollAcc) >= 40f) {
                                    val dir = if (scrollAcc > 0) 1 else -1
                                    vm.channel?.scroll(dir)
                                    scrollAcc -= dir * 40f
                                }
                            } else {
                                val d = active[0].positionChange()
                                if (d != Offset.Zero) {
                                    vm.channel?.move(d.x * sensitivity, d.y * sensitivity)
                                    moved += abs(d.x) + abs(d.y)
                                    val now = System.currentTimeMillis()
                                    val dt = (now - lastMove).coerceAtLeast(1)
                                    speed = (abs(d.x) + abs(d.y)) * 1000f / dt
                                    lastMove = now
                                }
                            }
                            active.forEach { it.consume() }
                        }

                        touching = false
                        if (dragLatched) {
                            vm.channel?.button("left", false)
                            dragLatched = false
                        }

                        // A short, still touch is a click. Two fingers = right.
                        val quick = System.currentTimeMillis() - downAt < 250
                        if (quick && moved < 18f && !holdDrag) {
                            if (pointers >= 2) {
                                vm.channel?.click("right")
                                flash("right")
                            } else {
                                vm.channel?.click("left")
                                flash("left")
                                lastTapAt = System.currentTimeMillis()
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        first.consume()
                    }
                }
        ) {
            DotGrid()

            if (!touching) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Mouse, null, tint = P.mute, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Drag to move · tap to click", color = P.mute, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Two fingers scroll · two-finger tap right-clicks",
                        color = P.mute, fontSize = 11.sp,
                    )
                }
            }
        }

        // ── scroll row ──────────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(P.panel),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SCROLL", color = P.mute, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                RepeatButton({ vm.channel?.scroll(1) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Scroll up", tint = P.sub,
                        modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                RepeatButton({ vm.channel?.scroll(-1) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Scroll down", tint = P.sub,
                        modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.width(1.dp).height(48.dp).background(P.border))
            Box(
                Modifier
                    .width(64.dp)
                    .height(48.dp)
                    .background(if (pressed == "double") P.green.copy(alpha = 0.13f) else Color.Transparent)
                    .tapTarget { vm.channel?.click("left", 2); flash("double") },
                contentAlignment = Alignment.Center,
            ) {
                Text("2×", color = if (pressed == "double") P.green else P.sub, fontSize = 12.sp)
            }
        }

        // ── click buttons ───────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(Modifier.fillMaxWidth().height(66.dp)) {
            listOf(
                Triple("left", "Left", P.accent),
                Triple("middle", "Mid", P.sub),
                Triple("right", "Right", P.purple),
            ).forEachIndexed { i, (key, label, colour) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxSize().background(P.border))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(
                            if (pressed == key) colour.copy(alpha = 0.13f) else P.surface
                        )
                        .tapTarget { vm.channel?.click(key); flash(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (pressed == key) colour else P.sub,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Tracks the last left-tap so a following drag can latch the button down. */
private var lastTapAt = 0L

/** Holding the button repeats the action, as a scroll wheel would. */
@Composable
private fun RepeatButton(onTick: () -> Unit, content: @Composable () -> Unit) {
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        onTick()
        delay(320)                       // initial hold before it starts repeating
        while (isActive && held) {
            onTick()
            delay(55)
        }
    }
    Box(
        Modifier
            .size(44.dp, 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (held) P.glow else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held = true
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        if (e.changes.none { it.pressed }) break
                    }
                    held = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The mock's faint dot lattice, drawn rather than tiled as an SVG pattern. */
@Composable
private fun DotGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 32.dp.toPx()
        val colour = P.accent.copy(alpha = 0.15f)
        var y = step
        while (y < size.height) {
            var x = step
            while (x < size.width) {
                drawCircle(colour, radius = 1.5f, center = Offset(x, y))
                x += step
            }
            y += step
        }
    }
}
