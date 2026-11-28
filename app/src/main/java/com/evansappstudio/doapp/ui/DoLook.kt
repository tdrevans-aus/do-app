package com.evansappstudio.doapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evansappstudio.doapp.R

/**
 * The design pass look, used first by the Spec 11 screens (Spec 14 restyles the rest).
 * Space Grotesk and Inter would need the downloadable-fonts dependency, so headings
 * use the system sans serif in bold with tighter tracking and body text the system sans serif.
 */
object DoLook {
    val Ground = Color(0xFFF7F8FA)
    val Ink = Color(0xFF111827)
    val Secondary = Color(0xFF4B5563)
    val Surface = Color(0xFFFFFFFF)
    val Line = Color(0xFFE5E7EB)

    /** Only for overdue, nagging and the buy button. */
    val Alarm = Color(0xFFC2391B)
    val CatalogueInk = Color(0xFF7A3E0B)
    val CatalogueGround = Color(0xFFFCEFD9)

    val MinTouch = 44.dp

    private val Heading = FontFamily.SansSerif
    private val Body = FontFamily.SansSerif

    val typography = Typography(
        headlineSmall = TextStyle(fontFamily = Heading, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
        titleLarge = TextStyle(fontFamily = Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontFamily = Heading, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
        titleSmall = TextStyle(fontFamily = Heading, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    )

    val colors = lightColorScheme(
        primary = Ink,
        onPrimary = Color.White,
        secondary = Secondary,
        background = Ground,
        onBackground = Ink,
        surface = Surface,
        onSurface = Ink,
        surfaceVariant = Ground,
        onSurfaceVariant = Secondary,
        outline = Line,
        error = Alarm,
    )
}

@Composable
fun DoLookTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DoLook.colors, typography = DoLook.typography, content = content)
}

/** Where a reminder came from: the amber Catalogue chip. */
@Composable
fun CatalogueChip(modifier: Modifier = Modifier) {
    Tag(stringResource(R.string.sysd_chip), DoLook.CatalogueInk, DoLook.CatalogueGround, modifier)
}

@Composable
fun Tag(text: String, ink: Color, ground: Color, modifier: Modifier = Modifier) {
    Surface(color = ground, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            text = text,
            color = ink,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

enum class DoTab { TODAY, CATALOGUE }

/** The bottom tabs. Spec 14 adds All and Settings. */
@Composable
fun DoTabs(selected: DoTab, onSelect: (DoTab) -> Unit) {
    DoLookTheme {
        Surface(color = DoLook.Surface) {
            androidx.compose.foundation.layout.Column {
                HorizontalDivider(color = DoLook.Line)
                Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    DoTab.entries.forEach { tab ->
                        val isSelected = tab == selected
                        TextButton(
                            onClick = { onSelect(tab) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = DoLook.MinTouch)
                                .semantics {
                                    role = Role.Tab
                                    this.selected = isSelected
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(if (tab == DoTab.TODAY) R.string.tab_today else R.string.tab_catalogue),
                                    color = if (isSelected) DoLook.Ink else DoLook.Secondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
