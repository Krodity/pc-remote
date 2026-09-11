package uk.krodity.pcremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.krodity.pcremote.ui.theme.Mono
import uk.krodity.pcremote.ui.theme.P

/**
 * A tap with no ripple, mirroring the mock's `WebkitTapHighlightColor:
 * transparent`. Colour change is the press feedback everywhere in this design,
 * so a Material ripple on top would read as a second, conflicting one.
 *
 * Haptics stand in for the physical click the app is imitating -- on a
 * trackpad or a key you get nothing else to confirm the press landed.
 */
fun Modifier.tapTarget(
    enabled: Boolean = true,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val hf = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
    ) {
        if (haptic) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** The recurring outlined tile: bordered, panel-filled, colour-shifts when on. */
@Composable
fun KeyTile(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = P.sub,
    mono: Boolean = true,
    fontSize: Int = 13,
    vertical: Dp = 12.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.13f) else P.surface)
            .border(1.dp, if (active) accent else P.border, RoundedCornerShape(8.dp))
            .tapTarget(onClick = onClick)
            .padding(vertical = vertical),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = accent,
            fontSize = fontSize.sp,
            fontFamily = if (mono) Mono else FontFamily.Default,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Small uppercase section heading, as used throughout the Keys panel. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = P.mute,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** A hairline divider in the border colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(P.border)
    )
}

/** The app's single text-input style: panel fill, thin border, no underline. */
@Composable
fun FieldStyle(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    singleLine: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, color = P.mute, fontSize = 14.sp) },
        singleLine = singleLine,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = TextStyle(
            fontSize = 15.sp,
            fontFamily = if (mono) Mono else FontFamily.Default,
        ),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = P.panel,
            unfocusedContainerColor = P.panel,
            focusedTextColor = P.text,
            unfocusedTextColor = P.text,
            cursorColor = P.accent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** A labelled two-line tile used by the Quick Combos grid. */
@Composable
fun ComboTile(combo: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(P.surface)
            .border(1.dp, P.border, RoundedCornerShape(8.dp))
            .tapTarget(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(combo, fontFamily = Mono, fontSize = 12.sp, color = P.text)
        Text(label, fontSize = 10.sp, color = P.mute)
    }
}

/** Border+fill wrapper for the panels that sit inside a scrolling column. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(10.dp),
    border: BorderStroke = BorderStroke(1.dp, P.border),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(P.surface)
            .border(border, RoundedCornerShape(8.dp))
            .padding(padding)
    ) { content() }
}
