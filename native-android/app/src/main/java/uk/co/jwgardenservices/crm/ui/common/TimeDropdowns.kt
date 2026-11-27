package uk.co.jwgardenservices.crm.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import java.util.Locale

/**
 * Drop-down pickers for times of day and for hours worked, so nothing has to be
 * typed. Values keep the shape the app already stores: "HH:mm" for a time, and
 * a plain decimal string ("2.5") for hours.
 */
object TimeChoices {
    /** Every quarter hour in the day, as "HH:mm". */
    private val QUARTER_HOURS: List<String> =
        (0 until 24 * 4).map { String.format(Locale.ROOT, "%02d:%02d", it / 4, it % 4 * 15) }

    /** The times to offer. A time already set that is off the grid (09:10) is kept, in order. */
    fun timeOptions(selected: String): List<String> {
        val current = selected.trim()
        val valid = Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(current)
        return if (valid && current !in QUARTER_HOURS) (QUARTER_HOURS + current).sorted() else QUARTER_HOURS
    }

    /** Quarter-hour steps from a quarter of an hour up to twelve hours. */
    private val HOUR_STEPS: List<Double> = (1..48).map { it * 0.25 }

    /** The hours to offer, as plain decimals. A figure already typed that is off the grid is kept. */
    fun hourOptions(selected: String): List<String> {
        val steps = HOUR_STEPS.map(::hoursText)
        val current = selected.trim().toDoubleOrNull()
        if (current == null || current <= 0 || current > 24) return steps
        val text = hoursText(current)
        return if (text in steps) steps else (HOUR_STEPS + current).sorted().map(::hoursText).distinct()
    }

    /** 2.0 becomes "2", 2.5 stays "2.5", 1.75 stays "1.75". */
    fun hoursText(value: Double): String =
        if (value == Math.floor(value)) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    /** "2.5" reads as "2½ hours"; "1.75" as "1h 45m". */
    fun hoursLabel(text: String): String {
        val value = text.trim().toDoubleOrNull() ?: return text
        val minutes = Math.round(value * 60).toInt()
        val whole = minutes / 60
        val rest = minutes % 60
        return when {
            minutes <= 0 -> text
            rest == 0 -> if (whole == 1) "1 hour" else "$whole hours"
            rest == 30 && whole == 0 -> "½ hour"
            rest == 30 -> "$whole½ hours"
            whole == 0 -> "${rest}m"
            else -> "${whole}h ${rest}m"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val options = remember(value) { TimeChoices.timeOptions(value) }
    PickerField(
        shown = value,
        options = options,
        optionLabel = { it },
        label = label,
        placeholder = "Pick a time",
        enabled = enabled,
        modifier = modifier,
        onPick = onValueChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoursDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Hours",
    enabled: Boolean = true,
) {
    val options = remember(value) { TimeChoices.hourOptions(value) }
    PickerField(
        shown = if (value.isBlank()) "" else TimeChoices.hoursLabel(value),
        options = options,
        optionLabel = TimeChoices::hoursLabel,
        label = label,
        placeholder = "Pick hours",
        enabled = enabled,
        modifier = modifier,
        onPick = onValueChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerField(
    shown: String,
    options: List<String>,
    optionLabel: (String) -> String,
    label: String,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onPick(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
