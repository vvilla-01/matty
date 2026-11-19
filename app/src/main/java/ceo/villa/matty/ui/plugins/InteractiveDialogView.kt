package ceo.villa.matty.ui.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ceo.villa.matty.data.api.DialogElement
import ceo.villa.matty.data.api.DialogOption
import ceo.villa.matty.data.api.OpenDialogRequest
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.chat.MarkdownText
import ceo.villa.matty.ui.components.MattyDialog
import ceo.villa.matty.ui.components.SwitchRow
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Plugin and integration dialogs (`open_dialog`), drawn natively: text, textarea, select
 * (static, users, channels), bool and radio elements, with per-field server errors.
 */
@Composable
fun InteractiveDialogView(req: OpenDialogRequest, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val scope = rememberCoroutineScope()
    val d = req.dialog
    val values = remember { mutableStateMapOf<String, String>().apply { d.elements.orEmpty().forEach { put(it.name, it.default) } } }
    val errors = remember { mutableStateMapOf<String, String>() }
    var general by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val channelId = repo.activeChannelId.value.orEmpty()
    val teamId = repo.channels.value[channelId]?.teamId?.ifEmpty { null } ?: repo.currentTeamId.value.orEmpty()

    fun submit(cancelled: Boolean) {
        if (cancelled) {
            if (d.notifyOnCancel) scope.launch {
                runCatching { repo.client.submitDialog(req.url, d.callbackId, d.state, repo.myId, channelId, teamId, emptyMap(), true) }
            }
            onClose(); return
        }
        errors.clear()
        d.elements.orEmpty().forEach { el ->
            if (!el.optional && values[el.name].isNullOrBlank() && el.type != "bool") errors[el.name] = "This field is required."
        }
        if (errors.isNotEmpty()) return
        busy = true
        scope.launch {
            val submission: Map<String, JsonElement> = d.elements.orEmpty().associate { el ->
                val v = values[el.name].orEmpty()
                el.name to when {
                    el.type == "bool" -> JsonPrimitive(v == "true")
                    el.subType == "number" -> v.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
                    else -> JsonPrimitive(v)
                }
            }
            runCatching { repo.client.submitDialog(req.url, d.callbackId, d.state, repo.myId, channelId, teamId, submission, false) }
                .onSuccess { r ->
                    if (r.errors.isNotEmpty() || r.error.isNotBlank()) {
                        errors.putAll(r.errors); general = r.error.ifBlank { null }
                    } else onClose()
                }
                .onFailure { general = it.message ?: "That didn't go through." }
            busy = false
        }
    }

    MattyDialog(d.title.ifBlank { "Plugin" }, onDismiss = { submit(true) }, icon = Icons.Outlined.Extension, actions = {
        TextButton(onClick = { submit(true) }) { Text("Cancel") }
        TextButton(onClick = { submit(false) }, enabled = !busy) { Text(d.submitLabel.ifBlank { "Submit" }) }
    }) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            if (d.introductionText.isNotBlank()) {
                MarkdownText(d.introductionText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(top = 10.dp))
            }
            d.elements.orEmpty().forEach { el ->
                DialogField(el, values[el.name].orEmpty(), errors[el.name]) { values[el.name] = it; errors.remove(el.name) }
                Spacer(Modifier.padding(top = 10.dp))
            }
            general?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DialogField(el: DialogElement, value: String, error: String?, onChange: (String) -> Unit) {
    val c = Matty.colors
    val repo = LocalRepo.current
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.rim)
    val label = el.displayName + if (el.optional) " (optional)" else ""
    when (el.type) {
        "bool" -> SwitchRow(label, el.helpText.ifBlank { el.placeholder.ifBlank { null } }, value == "true") { onChange(it.toString()) }
        "radio" -> Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            el.options.orEmpty().forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == o.value, onClick = { onChange(o.value) })
                    Text(o.text, color = c.text)
                }
            }
        }
        "select" -> {
            var expanded by remember { mutableStateOf(false) }
            var options by remember { mutableStateOf(el.options.orEmpty()) }
            var display by remember(value) { mutableStateOf(options.firstOrNull { it.value == value }?.text ?: value) }
            LaunchedEffect(el.dataSource) {
                when (el.dataSource) {
                    "users" -> options = runCatching { repo.client.autocompleteUsers("", repo.currentTeamId.value, null) }.getOrDefault(emptyList())
                        .map { DialogOption(repo.nameOf(it) + " (@${it.username})", it.id) }
                    "channels" -> options = repo.channels.value.values.filter { !it.isDmOrGm && it.teamId == repo.currentTeamId.value }
                        .map { DialogOption(it.displayName, it.id) }
                }
            }
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(
                    display, {}, readOnly = true, label = { Text(label) }, isError = error != null,
                    supportingText = (error ?: el.helpText.ifBlank { null })?.let { t -> { Text(t) } },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    shape = Matty.FieldShape, colors = colors,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    options.forEach { o -> DropdownMenuItem(text = { Text(o.text) }, onClick = { onChange(o.value); display = o.text; expanded = false }) }
                }
            }
        }
        else -> OutlinedTextField(
            value, { v -> onChange(if (el.maxLength > 0) v.take(el.maxLength) else v) },
            label = { Text(label) }, placeholder = el.placeholder.ifBlank { null }?.let { { Text(it) } },
            isError = error != null, supportingText = (error ?: el.helpText.ifBlank { null })?.let { t -> { Text(t) } },
            singleLine = el.type != "textarea", minLines = if (el.type == "textarea") 3 else 1,
            visualTransformation = if (el.subType == "password") androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when (el.subType) {
                "email" -> KeyboardType.Email; "number" -> KeyboardType.Number; "tel" -> KeyboardType.Phone
                "url" -> KeyboardType.Uri; "password" -> KeyboardType.Password; else -> KeyboardType.Text
            }),
            shape = Matty.FieldShape, colors = colors, modifier = Modifier.fillMaxWidth(),
        )
    }
}
