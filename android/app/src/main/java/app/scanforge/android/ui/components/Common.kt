package app.scanforge.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.geometry.ConfidenceLevel

enum class Status { GOOD, WARN, BAD, NEUTRAL }

fun Status.color(): Color = when (this) { Status.GOOD -> SfColors.Good; Status.WARN -> SfColors.Warn; Status.BAD -> SfColors.Bad; Status.NEUTRAL -> SfColors.Neutral }

/** Status indicator that never relies on color alone: icon + text + color. */
@Composable
fun StatusChip(status: Status, text: String, modifier: Modifier = Modifier) {
    val icon = when (status) { Status.GOOD -> Icons.Filled.CheckCircle; Status.WARN -> Icons.Filled.Warning; Status.BAD -> Icons.Filled.Error; Status.NEUTRAL -> Icons.Filled.HourglassEmpty }
    Surface(color = SfColors.PanelRaised.copy(alpha = 0.92f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, status.color().copy(alpha = 0.6f)), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = status.color(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = SfColors.Text)
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = SfColors.Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SfColors.Border), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = SfColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = SfColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = SfColors.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

fun confidenceStatus(level: String) = when (level.uppercase()) {
    ConfidenceLevel.HIGH.name -> Status.GOOD
    ConfidenceLevel.MEDIUM.name -> Status.WARN
    ConfidenceLevel.LOW.name -> Status.BAD
    else -> Status.NEUTRAL
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) SfColors.Bad else SfColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.2f GB".format(n / 1073741824.0)
}

fun Modifier.screenPadding() = this.padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp))

fun Modifier.describe(text: String) = this.semantics { contentDescription = text }
