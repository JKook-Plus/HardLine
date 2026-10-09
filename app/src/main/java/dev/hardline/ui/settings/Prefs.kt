package dev.hardline.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.hardline.core.Pref
import dev.hardline.core.Settings
import dev.hardline.core.state
import kotlin.math.roundToInt

@Composable
fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** The common layout of a settings row: title, optional summary, optional trailing widget. */
@Composable
fun PrefRow(
    title: String, summary: String? = null, icon: ImageVector? = null, enabled: Boolean = true,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .alpha(if (enabled) 1f else 0.45f).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 20.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrEmpty()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Box(Modifier.padding(start = 16.dp)) { trailing() }
    }
}

/**
 * A switch bound to a preference. [intercept] lets the caller do something first (ask for a
 * permission, confirm) and decide the final value by calling `apply`.
 */
@Composable
fun SwitchPref(
    s: Settings, pref: Pref<Boolean>, title: String, summary: String? = null, enabled: Boolean = true,
    intercept: ((wanted: Boolean, apply: (Boolean) -> Unit) -> Unit)? = null, onChanged: ((Boolean) -> Unit)? = null,
) {
    var value by s.state(pref)
    val change: (Boolean) -> Unit = { wanted ->
        val apply: (Boolean) -> Unit = { value = it; onChanged?.invoke(it) }
        if (intercept != null) intercept(wanted, apply) else apply(wanted)
    }
    PrefRow(title, summary, enabled = enabled, onClick = { change(!value) }) { Switch(value, change, enabled = enabled) }
}

@Composable
fun ChoicePref(
    s: Settings, pref: Pref<Int>, title: String, options: List<Pair<Int, String>>, enabled: Boolean = true, note: String? = null,
    onChanged: ((Int) -> Unit)? = null,
) {
    var value by s.state(pref)
    var open by remember { mutableStateOf(false) }
    PrefRow(title, options.firstOrNull { it.first == value }?.second ?: "", enabled = enabled, onClick = { open = true })
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                for ((v, label) in options) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { value = v; open = false; onChanged?.invoke(v) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(v == value, onClick = { value = v; open = false; onChanged?.invoke(v) })
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

/** A text field in a dialog, for strings and passwords. */
@Composable
fun TextDialog(
    title: String, initial: String, onDismiss: () -> Unit, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    lines: Int = 1, hint: String? = null, validate: (String) -> String? = { null }, onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var reveal by remember { mutableStateOf(!secret) }
    val problem = validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = lines == 1, minLines = lines, maxLines = maxOf(lines, 1) * 2,
                isError = problem != null, supportingText = (problem ?: hint)?.let { { Text(it) } },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
                trailingIcon = if (secret) ({
                    IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show") }
                }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = problem == null, onClick = { onSave(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextPref(
    s: Settings, pref: Pref<String>, title: String, empty: String = "Not set", secret: Boolean = false, enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1, hint: String? = null, validate: (String) -> String? = { null },
) {
    var value by s.state(pref)
    var open by remember { mutableStateOf(false) }
    PrefRow(title, when { value.isEmpty() -> empty; secret -> "•".repeat(value.length.coerceAtMost(12)); else -> value }, enabled = enabled, onClick = { open = true })
    if (open) TextDialog(title, value, { open = false }, secret, keyboard, lines, hint, validate) { value = if (lines == 1) it.trim() else it }
}

/** A whole number typed into a dialog. */
@Composable
fun NumberPref(s: Settings, pref: Pref<Int>, title: String, range: IntRange, enabled: Boolean = true, hint: String? = null, describe: (Int) -> String) {
    var value by s.state(pref)
    var open by remember { mutableStateOf(false) }
    PrefRow(title, describe(value), enabled = enabled, onClick = { open = true })
    if (open) TextDialog(
        title, value.toString(), { open = false }, keyboard = KeyboardType.Number, hint = hint ?: "${range.first} to ${range.last}",
        validate = { t -> if (t.trim().toIntOrNull()?.let { it in range } == true) null else "Enter a number from ${range.first} to ${range.last}" },
    ) { value = it.trim().toInt() }
}

@Composable
fun IntSliderPref(s: Settings, pref: Pref<Int>, title: String, range: IntRange, enabled: Boolean = true, describe: (Int) -> String = { "$it%" }) {
    var value by s.state(pref)
    var live by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(describe(live.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            live, { live = it }, enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { value = live.roundToInt() },
        )
    }
}

@Composable
fun FloatSliderPref(
    s: Settings, pref: Pref<Float>, title: String, range: ClosedFloatingPointRange<Float>, step: Float, enabled: Boolean = true,
    describe: (Float) -> String,
) {
    var value by s.state(pref)
    var live by remember(value) { mutableFloatStateOf(value) }
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(describe(snap(live)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(live, { live = it }, enabled = enabled, valueRange = range, onValueChangeFinished = { value = snap(live) })
    }
}

val positionNames = listOf(
    0 to "Top left", 4 to "Top centre", 1 to "Top right",
    5 to "Middle left", 8 to "Centre", 6 to "Middle right",
    2 to "Bottom left", 7 to "Bottom centre", 3 to "Bottom right",
)

/** Picks one of nine anchor positions from a small picture of the frame. */
@Composable
fun PositionPref(s: Settings, pref: Pref<Int>, title: String, enabled: Boolean = true) {
    var value by s.state(pref)
    var open by remember { mutableStateOf(false) }
    PrefRow(title, positionNames.firstOrNull { it.first == value }?.second, enabled = enabled, onClick = { open = true })
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (line in positionNames.chunked(3)) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((v, name) in line) {
                        val selected = v == value
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .clickable { value = v; open = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name, style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

private val swatches = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3, 0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63,
).map { it.toInt() }

/** Colour with opacity: swatches, an opacity slider and a hex field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPref(s: Settings, pref: Pref<Int>, title: String, enabled: Boolean = true) {
    var value by s.state(pref)
    var open by remember { mutableStateOf(false) }
    PrefRow(title, if (value ushr 24 == 0) "Transparent" else "#%08X".format(value), enabled = enabled, onClick = { open = true }) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(value)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
    if (!open) return
    var rgb by remember { mutableIntStateOf(value and 0xFFFFFF) }
    var alpha by remember { mutableFloatStateOf((value ushr 24) / 255f) }
    var hex by remember { mutableStateOf("%06X".format(value and 0xFFFFFF)) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in swatches) Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                            .border(if (c and 0xFFFFFF == rgb) 3.dp else 1.dp, if (c and 0xFFFFFF == rgb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { rgb = c and 0xFFFFFF; hex = "%06X".format(rgb); if (alpha == 0f) alpha = 1f },
                    )
                }
                OutlinedTextField(
                    hex, { t ->
                        hex = t.filter { it.isLetterOrDigit() }.take(6).uppercase()
                        hex.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { rgb = it }
                    },
                    label = { Text("Hex colour") }, prefix = { Text("#") }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                )
                Row(Modifier.padding(top = 12.dp)) {
                    Text("Opacity", Modifier.weight(1f))
                    Text("${(alpha * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(alpha, { alpha = it })
            }
        },
        confirmButton = { TextButton(onClick = { value = ((alpha * 255).roundToInt() shl 24) or rgb; open = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
