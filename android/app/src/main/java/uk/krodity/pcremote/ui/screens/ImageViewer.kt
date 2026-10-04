package uk.krodity.pcremote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import android.content.res.Resources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.krodity.pcremote.data.FsEntry
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.formatDate
import uk.krodity.pcremote.ui.formatSize
import uk.krodity.pcremote.ui.tapTarget
import uk.krodity.pcremote.ui.theme.P
import kotlin.math.abs
import kotlin.math.min

/**
 * The in-app image viewer.
 *
 * Images used to be the one thing the file browser could show a preview of but
 * not open — tapping one offered only the PC. It is now a first-class
 * destination: full screen, pinch to zoom, and swipe to walk the rest of the
 * folder, which is what makes it useful for going through a directory of
 * screenshots rather than a single file.
 *
 * Chrome hides on a tap so the picture gets the whole screen, and the bottom
 * bar keeps both escape hatches — the PC, and another app on this phone —
 * within reach without leaving the viewer.
 */
@Composable
fun ImageViewer(
    vm: RemoteViewModel,
    images: List<FsEntry>,
    startIndex: Int,
    onClose: () -> Unit,
    onOpenWith: (FsEntry) -> Unit,
    onOpenOnPc: (FsEntry) -> Unit,
) {
    if (images.isEmpty()) { onClose(); return }

    val state = rememberPagerState(
        initialPage = startIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size },
    )
    var chrome by remember { mutableStateOf(true) }
    // Hoisted out of the page so the pager can stop competing for horizontal
    // drags the moment the picture is zoomed in.
    var zoomed by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    // A page change always lands un-zoomed, so the flag must not survive it.
    LaunchedEffect(state.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = state,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                vm = vm,
                entry = images[page],
                active = page == state.currentPage,
                onZoomChanged = { if (page == state.currentPage) zoomed = it },
                onTap = { chrome = !chrome },
            )
        }

        val current = images[state.currentPage.coerceIn(0, images.lastIndex)]

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.66f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).tapTarget(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, "Close", tint = P.text,
                        modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current.name, color = P.text, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("${state.currentPage + 1} of ${images.size}")
                            append(" · ${formatSize(current.size)}")
                            formatDate(current.mtime).takeIf { it.isNotBlank() }
                                ?.let { append(" · $it") }
                        },
                        color = P.sub, fontSize = 11.sp, maxLines = 1,
                    )
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.66f))
                    .navigationBarsPadding()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ViewerAction(Icons.Filled.OpenInNew, "Open with") { onOpenWith(current) }
                ViewerAction(Icons.Filled.DesktopWindows, "Open on PC") { onOpenOnPc(current) }
            }
        }
    }
}

@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.tapTarget(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, label, tint = P.text, modifier = Modifier.size(18.dp))
        Text(label, color = P.sub, fontSize = 11.sp)
    }
}

/** Zoom ceiling. Past this even a full-resolution photo is only showing pixels. */
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f

@Composable
private fun ZoomableImage(
    vm: RemoteViewModel,
    entry: FsEntry,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val path = vm.child(entry.name)

    // Request roughly twice the screen's long edge: enough that a couple of
    // stops of zoom still show detail, without decoding a bitmap that dwarfs
    // anything the panel can draw. Read off the real display metrics rather
    // than LocalConfiguration, which is on its way out of Compose.
    val density = LocalDensity.current
    val target = remember {
        val dm = Resources.getSystem().displayMetrics
        (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceIn(512, 4096)
    }

    // The grid tile, if the browser already fetched one. Shown immediately so
    // the screen is never blank while the real render is on its way.
    var placeholder by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf<String?>(null) }

    // Neighbouring pages are composed by the pager before they are looked at;
    // only the visible one is worth a full-size decode.
    LaunchedEffect(path, active) {
        val c = vm.client ?: return@LaunchedEffect
        if (placeholder == null && full == null) {
            placeholder = runCatching { vm.thumbs.load(c, path, entry.mtime) }.getOrNull()
        }
        if (!active || full != null) return@LaunchedEffect
        val loaded = runCatching {
            vm.fullImages.load(c, path, entry.size, entry.mtime, target)
        }.getOrNull()
        if (loaded == null) failed = "could not decode ${entry.name}" else full = loaded
    }

    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val shown = full ?: placeholder

        /**
         * Keep the picture inside its own edges.
         *
         * The bound is the *drawn* size, not the box: an image letterboxed by
         * ContentScale.Fit has slack on one axis and none on the other, and
         * using the box for both lets a portrait shot slide off sideways.
         */
        fun clamp(next: Offset, s: Float): Offset {
            val b = shown ?: return Offset.Zero
            val aspect = b.width.toFloat() / b.height.toFloat()
            val drawW = min(boxW, boxH * aspect)
            val drawH = drawW / aspect
            val maxX = ((drawW * s - boxW) / 2f).coerceAtLeast(0f)
            val maxY = ((drawH * s - boxH) / 2f).coerceAtLeast(0f)
            return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
        }

        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    )
                    .pointerInput(path, shown) {
                        // Hand-rolled rather than `detectTransformGestures`,
                        // which consumes every drag it sees. Doing that here
                        // swallowed the pager's horizontal swipe: the picture
                        // is the full width of the page, so the pager never
                        // got an event and the folder could not be walked.
                        //
                        // So take the gesture only when this view is the one
                        // that needs it -- a pinch, or a pan while zoomed in --
                        // and otherwise leave the pointers untouched for the
                        // pager underneath.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val mine = pressed > 1 || scale > 1.01f
                                if (mine) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    // Zoom about the pinch centroid rather than
                                    // the middle of the screen, or the detail
                                    // being pinched at slides out from under
                                    // the fingers.
                                    val focus = Offset(
                                        centroid.x - boxW / 2f,
                                        centroid.y - boxH / 2f,
                                    )
                                    val moved = if (abs(next - scale) > 0.0001f) {
                                        (offset - focus) * (next / scale) + focus
                                    } else offset
                                    scale = next
                                    offset = clamp(moved + pan, next)
                                    onZoomChanged(scale > 1.01f)
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(path, shown) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { tap ->
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                    val focus = Offset(tap.x - boxW / 2f, tap.y - boxH / 2f)
                                    offset = clamp(-focus * (DOUBLE_TAP_SCALE - 1f), scale)
                                }
                                onZoomChanged(scale > 1.01f)
                            },
                        )
                    },
            )
        }

        // A placeholder is a 256 px tile; say so rather than let the user think
        // that blur is the file.
        if (full == null && failed == null) {
            CircularProgressIndicator(color = P.accent, strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp))
        }

        failed?.let {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it, color = P.sub, fontSize = 13.sp)
                Spacer(Modifier.size(8.dp))
                Text("Try opening it on the PC", color = P.mute, fontSize = 12.sp)
            }
        }
    }
}
