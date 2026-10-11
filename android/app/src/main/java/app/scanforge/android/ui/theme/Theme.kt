package app.scanforge.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SfColors {
    val Charcoal = Color(0xFF121417)
    val Panel = Color(0xFF1B1E22)
    val PanelRaised = Color(0xFF23272C)
    val Border = Color(0xFF2E333A)
    val Accent = Color(0xFF2EC4F1)
    val Text = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFFA7B0BA)
    // Status colors are always paired with an icon and text, never used alone.
    val Good = Color(0xFF3DDC84)
    val Warn = Color(0xFFF5B83D)
    val Bad = Color(0xFFF25C54)
    val Neutral = Color(0xFF8A939D)
}

private val scheme = darkColorScheme(
    primary = SfColors.Accent,
    onPrimary = Color(0xFF00141B),
    secondary = SfColors.Accent,
    background = SfColors.Charcoal,
    onBackground = SfColors.Text,
    surface = SfColors.Panel,
    onSurface = SfColors.Text,
    surfaceVariant = SfColors.PanelRaised,
    onSurfaceVariant = SfColors.TextSecondary,
    outline = SfColors.Border,
    error = SfColors.Bad,
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 16.sp)

@Composable
fun ScanForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
