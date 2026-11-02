package chimahon.novel.sync.kosync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Settings › Data and storage › Novels › KOReader sync. Laid out like the TTU sync screen. */
class KosyncSyncSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { Injekt.get<KosyncPreferences>() }
        val enabled by preferences.enabled().collectAsState()
        val direction by preferences.direction().collectAsState()
        val autoSyncOnOpen by preferences.autoSyncOnOpen().collectAsState()
        val autoSyncOnClose by preferences.autoSyncOnClose().collectAsState()
        val autoSyncPeriodic by preferences.autoSyncPeriodic().collectAsState()
        val autoSyncIntervalMins by preferences.autoSyncIntervalMins().collectAsState()
        val serverType by preferences.serverType().collectAsState()
        var directionMenuExpanded by remember { mutableStateOf(false) }
        var serverTypeMenuExpanded by remember { mutableStateOf(false) }
        var showAutoSyncDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = "KOReader sync", navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                PreferenceGroupHeader(title = "KOReader sync")

                SwitchPreferenceWidget(
                    title = "Enable",
                    subtitle = "Sync novel reading positions with KOReader devices through a kosync server",
                    checked = enabled,
                    onCheckedChanged = { preferences.enabled().set(it) },
                )

                TextPreferenceWidget(
                    title = "Direction",
                    subtitle = "Auto resolves by newest timestamp; Manual uses the library selection actions",
                    widget = {
                        Box {
                            TextButton(onClick = { directionMenuExpanded = true }) {
                                Text(DIRECTIONS[direction] ?: direction)
                            }
                            DropdownMenu(
                                expanded = directionMenuExpanded,
                                onDismissRequest = { directionMenuExpanded = false },
                            ) {
                                DIRECTIONS.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            directionMenuExpanded = false
                                            preferences.direction().set(value)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )

                val autoSyncSubtitle = remember(autoSyncOnOpen, autoSyncOnClose, autoSyncPeriodic) {
                    val parts = mutableListOf<String>()
                    if (autoSyncOnOpen) parts.add("On book open")
                    if (autoSyncOnClose) parts.add("On book close")
                    if (autoSyncPeriodic) parts.add("Periodic while reading")
                    if (parts.isEmpty()) "Disabled" else parts.joinToString(", ")
                }

                TextPreferenceWidget(
                    title = "Auto Sync",
                    subtitle = autoSyncSubtitle,
                    onPreferenceClick = { showAutoSyncDialog = true },
                )

                if (autoSyncPeriodic) {
                    var intervalMenuExpanded by remember { mutableStateOf(false) }
                    val intervalOptions = listOf(1, 3, 10, 15, 30)
                    TextPreferenceWidget(
                        title = "Periodic sync interval",
                        subtitle = "$autoSyncIntervalMins minutes",
                        onPreferenceClick = { intervalMenuExpanded = true },
                        widget = {
                            Box {
                                DropdownMenu(
                                    expanded = intervalMenuExpanded,
                                    onDismissRequest = { intervalMenuExpanded = false },
                                ) {
                                    intervalOptions.forEach { mins ->
                                        DropdownMenuItem(
                                            text = { Text("$mins minutes") },
                                            onClick = {
                                                intervalMenuExpanded = false
                                                preferences.autoSyncIntervalMins().set(mins)
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }

                if (showAutoSyncDialog) {
                    AlertDialog(
                        onDismissRequest = { showAutoSyncDialog = false },
                        title = { Text("Auto Sync Triggers") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TriggerRow("On book open", autoSyncOnOpen, preferences.autoSyncOnOpen())
                                TriggerRow("On book close", autoSyncOnClose, preferences.autoSyncOnClose())
                                TriggerRow("Periodic while reading", autoSyncPeriodic, preferences.autoSyncPeriodic())
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAutoSyncDialog = false }) {
                                Text("OK")
                            }
                        },
                    )
                }

                PreferenceGroupHeader(title = "Server")

                TextPreferenceWidget(
                    title = "Server type",
                    subtitle = SERVER_TYPES[serverType] ?: serverType,
                    onPreferenceClick = { serverTypeMenuExpanded = true },
                    widget = {
                        Box {
                            DropdownMenu(
                                expanded = serverTypeMenuExpanded,
                                onDismissRequest = { serverTypeMenuExpanded = false },
                            ) {
                                SERVER_TYPES.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            serverTypeMenuExpanded = false
                                            preferences.serverType().set(value)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )

                KosyncLogin(preferences)
            }
        }
    }

    private companion object {
        val DIRECTIONS = linkedMapOf(
            KosyncPreferences.DIRECTION_AUTO to "Auto",
            KosyncPreferences.DIRECTION_MANUAL to "Manual",
        )

        val SERVER_TYPES = linkedMapOf(
            KosyncPreferences.SERVER_TYPE_KOSYNC to "KOReader sync server (kosync)",
            KosyncPreferences.SERVER_TYPE_CWA to "Calibre-Web Automated",
        )
    }
}

/** One checkbox of the Auto Sync Triggers dialog; the whole row toggles it, as in the TTU dialog. */
@Composable
private fun TriggerRow(label: String, checked: Boolean, preference: Preference<Boolean>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { preference.set(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { preference.set(it) })
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The login form. A login is saved only after the server has accepted it. */
@Composable
private fun KosyncLogin(preferences: KosyncPreferences) {
    val scope = rememberCoroutineScope()
    val session = remember { Injekt.get<KosyncSession>() }
    val serverType by preferences.serverType().collectAsState()
    val isCwa = serverType == KosyncPreferences.SERVER_TYPE_CWA
    // Saveable, so what was typed survives the form scrolling out of the list. Not the password.
    var serverUrl by rememberSaveable { mutableStateOf(preferences.serverUrl().get()) }
    var username by rememberSaveable { mutableStateOf(preferences.username().get()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(preferences.credentials() != null) }

    fun report(text: String, error: Boolean) {
        message = text
        isError = error
    }

    fun submit(register: Boolean) {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            report("Enter the server, username and password first.", true)
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                val credentials = KosyncPreferences.credentialsFor(serverUrl, username, password, basicAuth = isCwa)
                session.signIn(credentials, register)
                password = ""
                signedIn = true
                val confirmation = when {
                    register -> "Account created and signed in."
                    preferences.enabled().get() -> "Signed in."
                    else -> "Signed in. Sync starts once Enable is switched on."
                }
                report(confirmation, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e.kosyncUserMessage(), true)
            } finally {
                busy = false
            }
        }
    }

    val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text(if (isCwa) "https://host/kosync" else "https://host:7200") },
            singleLine = true,
            keyboardOptions = plain.copy(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = plain,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (signedIn) "Password (stored as a hash)" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = plain.copy(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { submit(register = false) }, enabled = !busy) {
                Text("Sign in")
            }
            if (!isCwa) {
                OutlinedButton(onClick = { submit(register = true) }, enabled = !busy) {
                    Text("Register")
                }
            }
            if (signedIn) {
                TextButton(
                    onClick = {
                        session.signOut()
                        password = ""
                        signedIn = false
                        report("Signed out. This device no longer syncs.", false)
                    },
                    enabled = !busy,
                ) {
                    Text("Sign out")
                }
            }
        }
        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = (
                if (isCwa) {
                    "Calibre-Web Automated checks your CWA password itself, so it is stored on this device " +
                        "(private settings; left out of backups unless you include them). " +
                        "Use the address of your CWA server followed by /kosync. "
                } else {
                    "The password is stored only as the MD5 hash KOReader authenticates with. "
                }
                ) +
                "A book syncs when its EPUB file is kept in the library (books imported from a file or " +
                "downloaded from an OPDS catalog); KOReader matches the same file by its content.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
