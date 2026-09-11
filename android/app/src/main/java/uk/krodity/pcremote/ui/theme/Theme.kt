package uk.krodity.pcremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The mock's palette, carried over unchanged.
 *
 * It is a deliberately fixed dark scheme rather than a Material You one: the
 * design is a specific instrument panel, and letting the wallpaper recolour it
 * would lose the meaning the colours carry (amber = directory, red = danger,
 * purple = modifier, cyan = the one interactive accent).
 */
object P {
    val bg = Color(0xFF07101C)
    val nav = Color(0xFF0B1523)
    val surface = Color(0xFF0F1B2D)
    val panel = Color(0xFF132237)
    val border = Color(0xFF1A2F4A)
    val accent = Color(0xFF22D3EE)
    val accentD = Color(0xFF0891B2)
    val glow = Color(0x1F22D3EE)
    val green = Color(0xFF34D399)
    val red = Color(0xFFF87171)
    val amber = Color(0xFFFBBF24)
    val purple = Color(0xFFA78BFA)
    val text = Color(0xFFC8DDEF)
    val sub = Color(0xFF5C7FA3)
    val mute = Color(0xFF1E3350)
    val term = Color(0xFF050C18)
}

/** The mock's `P.mono`. Monospace is the platform's JetBrains-alike stand-in. */
val Mono = FontFamily.Monospace

private val scheme = darkColorScheme(
    primary = P.accent,
    onPrimary = Color.Black,
    secondary = P.purple,
    background = P.bg,
    onBackground = P.text,
    surface = P.surface,
    onSurface = P.text,
    surfaceVariant = P.panel,
    onSurfaceVariant = P.sub,
    outline = P.border,
    error = P.red,
)

private val typography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PcRemoteTheme(
    @Suppress("UNUSED_PARAMETER") dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
