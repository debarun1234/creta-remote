package com.debarun.cretaremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF07080B)
    val Surface = Color(0xFF101218)
    val Surface2 = Color(0xFF171A22)
    val Line = Color(0xFF242833)
    val Red = Color(0xFFE3192D)
    val RedHi = Color(0xFFFF4A52)
    val RedDeep = Color(0xFF8E0A14)
    val Text = Color(0xFFF2F4F8)
    val Dim = Color(0xFF8B93A3)
    val Faint = Color(0xFF565D6C)
    val Cyan = Color(0xFF3DD6F5)
    val Amber = Color(0xFFFFB020)
    val Green = Color(0xFF36E39A)
}

@Composable
fun CretaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Red, onPrimary = C.Text, background = C.Bg, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text, secondary = C.Cyan,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides C.Text) { content() }
    }
}

/** Single text primitive so the whole app shares one typographic voice. */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = C.Text,
    weight: FontWeight = FontWeight.Normal,
    spacing: TextUnit = TextUnit.Unspecified,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        style = TextStyle(
            fontSize = size,
            fontWeight = weight,
            letterSpacing = spacing,
            lineHeight = lineHeight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}

/** Small all-caps eyebrow label. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = C.Dim) {
    Txt(text.uppercase(), modifier, size = 11.sp, color = color, weight = FontWeight.Medium, spacing = 1.4.sp)
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        (tint?.copy(alpha = 0.10f) ?: C.Surface2).let { if (tint != null) it.compositeOverSolid(C.Surface) else it },
                        C.Surface,
                    ),
                ),
            )
            .border(1.dp, C.Line, shape),
        content = content,
    )
}

private fun Color.compositeOverSolid(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}
