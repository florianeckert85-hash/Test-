package de.leserkonto.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Intent
import android.view.autofill.AutofillManager
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.leserkonto.app.data.model.Loan
import de.leserkonto.app.ui.AppViewModel
import de.leserkonto.app.ui.UiState
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Wires a text field into the Android Autofill framework so password managers
 * (e.g. Google Passwortmanager) can offer to fill — and later save — the value.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.autofill(
    types: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val node = remember { AutofillNode(autofillTypes = types, onFill = onFill) }
    LocalAutofillTree.current += node
    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) requestAutofillForNode(node)
                else cancelAutofillForNode(node)
            }
        }
}

@Composable
fun LeserkontoApp(state: UiState, vm: AppViewModel) {
    if (!state.loggedIn) {
        LoginScreen(state, vm)
    } else {
        MainScreen(state, vm)
    }
}

// ---------------------------------------------------------------------- login

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(state: UiState, vm: AppViewModel) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val context = LocalContext.current
    val autofillManager = remember { context.getSystemService(AutofillManager::class.java) }

    // When a diagnostic report is ready, open the system share sheet so the
    // user can send it (e.g. by email) for troubleshooting the login.
    LaunchedEffect(state.diagnostics) {
        val report = state.diagnostics ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Leserkonto Login-Diagnose")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(send, "Diagnose teilen"))
        vm.consumeDiagnostics()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Leserkonto", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Stadtbibliothek Wehr · Komm.ONE",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Ausweisnummer") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .autofill(types = listOf(AutofillType.Username), onFill = { user = it }),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Passwort") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .autofill(types = listOf(AutofillType.Password), onFill = { pass = it }),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Die Zugangsdaten werden nur verschlüsselt auf diesem Gerät gespeichert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                // Ask Android/Google to offer saving the entered credentials.
                autofillManager?.commit()
                vm.login(user.trim(), pass)
            },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text("Anmelden & speichern")
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { vm.diagnose(user.trim(), pass) },
            enabled = !state.loading,
        ) {
            Text("Anmeldung schlägt fehl? Diagnose erstellen & teilen")
        }
        TextButton(onClick = { vm.shareLastCrash() }) {
            Text("Letzten Absturz-Bericht teilen")
        }
        Text(
            "Erstellt einen technischen Bericht der Login-Seite zum Teilen. " +
                "Das Passwort wird nicht aufgenommen; bei erfolgreichem Login können " +
                "aber Kontodaten enthalten sein.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------- main

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, vm: AppViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // System back closes the settings screen instead of leaving the app.
    BackHandler(enabled = showSettings) { showSettings = false }

    LaunchedEffect(state.message, state.error) {
        val text = state.message ?: state.error
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.consumeMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "Einstellungen" else "Geliehene Medien") },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                        }
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                    IconButton(onClick = { vm.logout() }) {
                        Icon(Icons.Default.Logout, contentDescription = "Abmelden")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (showSettings) {
                SettingsScreen(state, vm)
            } else {
                AccountScreen(state, vm)
            }
        }
    }
}

// -------------------------------------------------------------------- account

@Composable
fun AccountScreen(state: UiState, vm: AppViewModel) {
    val account = state.account
    val loans = account?.loans.orEmpty()

    Column(Modifier.fillMaxSize()) {
        if (loans.any { it.renewable }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { vm.renewAll() }, enabled = !state.loading) {
                    Icon(Icons.Default.Autorenew, contentDescription = null)
                    Text("  Alles verlängern")
                }
            }
        }

        if (state.loading && loans.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Column
        }

        if (loans.isEmpty()) {
            EmptyState(state)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            account?.fees?.takeIf { it.isNotBlank() }?.let { fees ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            "Gebühren: $fees",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            // Index-based keys: titles can repeat, and duplicate LazyColumn keys
            // crash the app, so we deliberately do not key by id/title.
            itemsIndexed(loans) { _, loan ->
                LoanCard(loan, isRenewing = state.renewingId == (loan.id ?: loan.title)) {
                    vm.renew(loan)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(state: UiState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (state.error != null) "Konnte Konto nicht laden." else "Keine ausgeliehenen Medien.",
            style = MaterialTheme.typography.titleMedium,
        )
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun LoanCard(loan: Loan, isRenewing: Boolean, onRenew: () -> Unit) {
    val days = loan.daysUntilDue()
    val (dueText, dueColor) = when {
        days == null -> ("Fälligkeit unbekannt" to MaterialTheme.colorScheme.onSurfaceVariant)
        days < 0 -> ("Überfällig seit ${-days} Tg." to MaterialTheme.colorScheme.error)
        days == 0L -> ("Heute fällig" to MaterialTheme.colorScheme.error)
        days <= 3 -> ("Fällig in $days Tg." to MaterialTheme.colorScheme.error)
        else -> ("Fällig in $days Tg." to MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                loan.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            loan.author?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                loan.dueDate?.let {
                    Text(it.format(dateFmt), style = MaterialTheme.typography.bodyMedium)
                    Text("  ·  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(dueText, color = dueColor, style = MaterialTheme.typography.bodyMedium)
            }
            loan.renewalsRemaining?.let {
                Text("Noch $it× verlängerbar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (loan.renewable) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRenew, enabled = !isRenewing) {
                        if (isRenewing) {
                            CircularProgressIndicator(Modifier.height(18.dp))
                        } else {
                            Icon(Icons.Default.Autorenew, contentDescription = null)
                            Text("  Verlängern")
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- settings

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val s = state.settings
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingRow(
            title = "Benachrichtigungen",
            subtitle = "Erinnerung als Push, wenn Medien bald fällig sind",
        ) {
            androidx.compose.material3.Switch(
                checked = s.notificationsEnabled,
                onCheckedChange = { vm.setNotifications(it) },
            )
        }

        StepperRow(
            title = "Erinnerung vorab",
            value = s.reminderDaysBefore,
            suffix = if (s.reminderDaysBefore == 1) "Tag" else "Tage",
            range = 0..14,
            onChange = { vm.setReminderDays(it) },
        )

        SettingRow(
            title = "Automatisch verlängern",
            subtitle = "Verlängert verlängerbare Medien kurz vor Fälligkeit von selbst",
        ) {
            androidx.compose.material3.Switch(
                checked = s.autoRenew,
                onCheckedChange = { vm.setAutoRenew(it) },
            )
        }

        if (s.autoRenew) {
            StepperRow(
                title = "Auto-Verlängerung",
                value = s.autoRenewDaysBefore,
                suffix = "Tage vorher",
                range = 1..7,
                onChange = { vm.setAutoRenewDays(it) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Hinweis: Die App liest dein Konto direkt vom Bibliotheksportal aus. " +
                "Eine automatische Verlängerung gelingt nur, wenn das Medium nicht " +
                "vorgemerkt ist und das Verlängerungslimit nicht erreicht wurde.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: Int,
    suffix: String,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { if (value > range.first) onChange(value - 1) }) { Text("−") }
        Text("$value $suffix", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { if (value < range.last) onChange(value + 1) }) { Text("+") }
    }
}
