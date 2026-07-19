package com.nachogoro.smartchess.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import scp.protocol.ParameterDescriptor
import scp.protocol.ParameterType

/**
 * Form state for a list of [ParameterDescriptor]s.
 *
 * Every value is held as a string regardless of declared type and converted only on submit. That
 * keeps partially-typed input (an empty numeric field, a half-typed number) representable without
 * fighting the text fields.
 */
class ParameterFormState(
    val descriptors: List<ParameterDescriptor>,
    initialValues: Map<String, String>,
) {
    val fields: Map<String, MutableState<String>> = descriptors.associate { descriptor ->
        val initial = initialValues[descriptor.name]
            ?: descriptor.default?.toString()
            ?: ""
        descriptor.name to mutableStateOf(initial)
    }

    /**
     * Whether a parameter should currently be shown.
     *
     * A descriptor may declare that it is only relevant when another field has a particular value
     * (for example, an opponent's username only matters when the seek type is "challenge"). Since
     * this reads the referenced field's snapshot state, toggling that field recomposes and shows
     * or hides dependants immediately.
     */
    fun isVisible(descriptor: ParameterDescriptor): Boolean {
        val condition = descriptor.visibleWhen ?: return true
        val current = fields[condition.field]?.value ?: return true
        return when {
            condition.equals != null -> current == condition.equals.toString()
            condition.oneOf != null -> current in condition.oneOf!!.map { it.toString() }
            else -> true
        }
    }

    /** Descriptors that are visible, and therefore the only ones the user must fill in. */
    fun visibleDescriptors(): List<ParameterDescriptor> = descriptors.filter { isVisible(it) }

    /** The first validation problem among visible fields, or null when the form is valid. */
    fun validationError(): String? {
        for (descriptor in visibleDescriptors()) {
            val raw = fields[descriptor.name]?.value.orEmpty()
            if (descriptor.required && raw.isBlank()) {
                return "${descriptor.description} is required"
            }
            if (descriptor.type == ParameterType.SPIN && raw.isNotBlank()) {
                val number = raw.toIntOrNull()
                    ?: return "${descriptor.description} must be a whole number"
                val min = descriptor.min
                val max = descriptor.max
                if (min != null && number < min) return "${descriptor.description} must be at least $min"
                if (max != null && number > max) return "${descriptor.description} must be at most $max"
            }
        }
        return null
    }

    /** Typed values for the visible fields, ready to hand back to the adapter. */
    fun toTypedValues(): Map<String, Any> = visibleDescriptors().associate { descriptor ->
        val raw = fields[descriptor.name]?.value.orEmpty()
        val value: Any = when (descriptor.type) {
            ParameterType.SPIN ->
                raw.toIntOrNull() ?: (descriptor.default as? Number)?.toInt() ?: 0

            ParameterType.CHECK ->
                raw.toBooleanStrictOrNull() ?: (descriptor.default as? Boolean) ?: false

            ParameterType.COMBO, ParameterType.STRING -> raw
        }
        descriptor.name to value
    }

    /** Raw values, for persisting so the user does not retype them next time. */
    fun toStringValues(): Map<String, String> =
        visibleDescriptors().associate { it.name to fields[it.name]?.value.orEmpty() }
}

@Composable
fun rememberParameterFormState(
    descriptors: List<ParameterDescriptor>,
    initialValues: Map<String, String>,
): ParameterFormState = remember(descriptors, initialValues) {
    ParameterFormState(descriptors, initialValues)
}

/** Renders every currently-visible parameter as an appropriate input widget. */
@Composable
fun ParameterFormFields(state: ParameterFormState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        for (descriptor in state.descriptors) {
            if (!state.isVisible(descriptor)) continue
            val fieldState = state.fields[descriptor.name] ?: continue
            ParameterField(descriptor, fieldState)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterField(descriptor: ParameterDescriptor, state: MutableState<String>) {
    when (descriptor.type) {
        ParameterType.COMBO -> {
            val options = descriptor.options.orEmpty()
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = state.value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(descriptor.description) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (option in options) {
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                state.value = option
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        ParameterType.CHECK -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.value.toBooleanStrictOrNull() ?: false,
                    onCheckedChange = { state.value = it.toString() },
                )
                Text(descriptor.description, style = MaterialTheme.typography.bodyMedium)
            }
        }

        ParameterType.SPIN -> {
            OutlinedTextField(
                value = state.value,
                onValueChange = { new -> state.value = new.filter { it.isDigit() || it == '-' } },
                label = { Text(descriptor.description) },
                supportingText = descriptor.rangeLabel()?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ParameterType.STRING -> {
            OutlinedTextField(
                value = state.value,
                onValueChange = { state.value = it },
                label = { Text(descriptor.description) },
                singleLine = true,
                // Tokens and passwords are masked, but still persisted so they are typed once.
                visualTransformation = if (descriptor.sensitive) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (descriptor.sensitive) KeyboardType.Password else KeyboardType.Text
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun ParameterDescriptor.rangeLabel(): String? {
    val min = min
    val max = max
    return when {
        min != null && max != null -> "$min - $max"
        min != null -> "at least $min"
        max != null -> "at most $max"
        else -> null
    }
}
