package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.VaultDraft
import io.github.nideta231.hermesremote.data.VaultItem
import io.github.nideta231.hermesremote.data.VaultState

class VaultActions(
    val refresh: () -> Unit,
    /** (draft, item it replaces or null, onDone(error or null)). */
    val save: (VaultDraft, VaultItem?, (String?) -> Unit) -> Unit,
    val remove: (VaultItem) -> Unit,
)

private val kindTitles = linkedMapOf("login" to "Logins", "payment" to "Cards", "address" to "Addresses")

/** Hermes' vault on the PC: what the agent may fill into web pages. Secrets are never shown or sent back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(state: VaultState, actions: VaultActions, onBack: () -> Unit) {
    LaunchedEffect(Unit) { actions.refresh() }
    var editing by remember { mutableStateOf<VaultItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VaultItem?>(null) }
    PageScaffold("Vault", onBack) {
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Logins, cards and addresses Hermes can fill in for you. Passwords stay on the PC; the phone never shows them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    if (!state.unsupported) TextButton(onClick = { adding = true }) {
                        Icon(Glyphs.Plus, null, tint = Gold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = Gold)
                    }
                }
                when {
                    state.unsupported -> VaultNote("Your PC runs an older bridge without the vault page. Update it: run install.sh on the PC.")
                    state.error != null -> VaultNote(state.error, Bad)
                    state.items.isEmpty() && !state.loading -> VaultNote("Nothing saved yet.")
                }
                state.sources.filter { it.enabled && it.needsUnlock && !it.unlocked }.forEach {
                    VaultNote("${it.displayName} is locked. Unlock it on the PC to see its logins here.")
                }
                kindTitles.forEach { (kind, title) ->
                    val group = state.items.filter { it.kind == kind }
                    if (group.isEmpty()) return@forEach
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    Panel {
                        group.forEachIndexed { i, item ->
                            if (i > 0) PanelDivider()
                            PanelRow(item.label, subtitle = subtitle(item), icon = iconFor(item.kind)) {
                                if (item.editable) {
                                    IconButton(onClick = { editing = item }) { Icon(Glyphs.Edit, "Edit", Modifier.size(18.dp)) }
                                    IconButton(onClick = { deleting = item }) { Icon(Glyphs.Trash, "Delete", Modifier.size(18.dp), tint = Bad) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (adding) VaultItemDialog(null, actions.save) { adding = false }
    editing?.let { item -> VaultItemDialog(item, actions.save) { editing = null } }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${item.label}”?") },
            text = { Text("Hermes won't be able to fill it in any more. This can't be undone.") },
            confirmButton = { TextButton(onClick = { actions.remove(item); deleting = null }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

private fun subtitle(item: VaultItem): String = listOfNotNull(
    item.identifier, item.origin?.removePrefix("https://")?.removePrefix("http://"),
    if (item.hasOtp) "2FA" else null, if (!item.editable) item.backend else null,
).joinToString(" · ").ifEmpty { item.kind }

private fun iconFor(kind: String) = when (kind) {
    "login" -> Glyphs.Person
    "payment" -> Glyphs.Shield
    else -> Glyphs.Folder
}

@Composable
private fun VaultNote(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
}

/**
 * Add, or edit [item]. Editing re-enters the secret (the phone never receives the stored one):
 * the new version is saved and the old one removed.
 */
@Composable
private fun VaultItemDialog(item: VaultItem?, save: (VaultDraft, VaultItem?, (String?) -> Unit) -> Unit, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(item?.kind ?: "login") }
    var label by remember { mutableStateOf(item?.label.orEmpty()) }
    var origin by remember { mutableStateOf(item?.origin.orEmpty()) }
    val fields = remember { mutableStateOf(mapOf("identifier" to item?.identifier.orEmpty())) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun field(key: String) = fields.value[key].orEmpty()
    fun set(key: String, v: String) { fields.value = fields.value + (key to v) }

    @Composable
    fun input(key: String, title: String, secret: Boolean = false, number: Boolean = false, hint: String? = null) {
        OutlinedTextField(field(key), { set(key, it) }, label = { Text(title) }, singleLine = true,
            placeholder = hint?.let { { Text(it) } },
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                secret -> KeyboardType.Password
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            }), modifier = Modifier.fillMaxWidth())
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(if (item == null) "Add to vault" else "Edit “${item.label}”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item == null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kindTitles.forEach { (k, t) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(t.removeSuffix("s")) }) }
                }
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(origin, { origin = it }, label = { Text(if (kind == "login") "Site" else "Site (optional)") },
                    placeholder = { Text("https://example.com") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                when (kind) {
                    "login" -> {
                        input("identifier", "Email or username")
                        input("password", "Password", secret = true)
                        input("otp_secret", "2FA key (optional)", secret = true)
                    }
                    "payment" -> {
                        input("card_number", "Card number", number = true)
                        input("cardholder_name", "Name on card")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) { input("exp_month", "MM", number = true) }
                            Column(Modifier.weight(1f)) { input("exp_year", "YYYY", number = true) }
                            Column(Modifier.weight(1f)) { input("cvc", "CVC", secret = true, number = true) }
                        }
                        input("billing_postal_code", "Postal code")
                    }
                    else -> {
                        input("address_line1", "Street address")
                        input("address_line2", "Apt, suite (optional)")
                        input("city", "City")
                        input("state", "Province / state (optional)")
                        input("postal_code", "Postal code")
                        input("country", "Country", hint = "ID")
                    }
                }
                if (item != null) Text("The saved password/card isn't shown. Enter it again; the old version is replaced.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true; error = null
                val secret = fields.value.mapValues { (k, v) -> if (k == "password" || k == "cvc") v else v.trim() }
                    .let { if (kind == "login") it + ("identifier_type" to if ("@" in it["identifier"].orEmpty()) "email" else "username") else it }
                    .let { if (kind == "payment") it + ("card_number" to it["card_number"].orEmpty().replace(" ", "")) else it }
                save(VaultDraft(kind, label, origin, secret), item) { err -> saving = false; if (err == null) onClose() else error = err }
            }) { Text(if (saving) "Saving…" else "Save", color = Gold) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("Cancel") } },
    )
}
