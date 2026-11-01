package chimahon.novel.sync.kosync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Settings › Data and storage › Novels › KOReader sync. */
class KosyncSyncSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { Injekt.get<KosyncPreferences>() }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = "KOReader sync", navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
        ) { contentPadding ->
            PreferenceScreen(
                items = listOf(
                    Preference.PreferenceGroup(
                        title = "KOReader sync",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.SwitchPreference(
                                preference = preferences.enabled(),
                                title = "Enable",
                                subtitle = "Sync novel reading positions with KOReader devices through a kosync server",
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Server",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.ListPreference(
                                preference = preferences.serverType(),
                                entries = persistentMapOf(
                                    KosyncPreferences.SERVER_TYPE_KOSYNC to "KOReader sync server (kosync)",
                                    KosyncPreferences.SERVER_TYPE_CWA to "Calibre-Web Automated",
                                ),
                                title = "Server type",
                            ),
                            Preference.PreferenceItem.CustomPreference(title = "Server") { KosyncLogin(preferences) },
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Behaviour",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.SwitchPreference(
                                preference = preferences.autoSync(),
                                title = "Sync on open and resume",
                                subtitle = "Move to a newer position from another device when a book opens or " +
                                    "comes back after a while",
                            ),
                            Preference.PreferenceItem.SwitchPreference(
                                preference = preferences.push(),
                                title = "Push progress on close",
                                subtitle = "Send this device's position when a book is closed",
                            ),
                        ),
                    ),
                ),
                contentPadding = contentPadding,
            )
        }
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
