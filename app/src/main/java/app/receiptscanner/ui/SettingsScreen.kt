package app.receiptscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.receiptscanner.scan.ReceiptNamer
import app.receiptscanner.settings.Settings
import app.receiptscanner.settings.SettingsRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    repo: SettingsRepository,
    onConnectDrive: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var folder by rememberSaveable { mutableStateOf(settings.folderName) }

    fun saveAndExit() {
        scope.launch { repo.setFolderName(folder.trim().ifEmpty { Settings.DEFAULT_FOLDER }) }
        onBack()
    }
    BackHandler(onBack = ::saveAndExit)

    var showTripDialog by rememberSaveable { mutableStateOf(false) }
    if (showTripDialog) {
        StartTripDialog(
            onStart = { tripFolder ->
                scope.launch { repo.setTripFolder(tripFolder) }
                showTripDialog = false
            },
            onDismiss = { showTripDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = ::saveAndExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Google Drive") },
                supportingContent = {
                    Text(if (settings.driveConnected) "Connected" else "Not connected — you'll be asked on first upload")
                },
                trailingContent = {
                    OutlinedButton(onClick = onConnectDrive) {
                        Text(if (settings.driveConnected) "Reconnect" else "Connect")
                    }
                },
            )
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Drive folder") },
                singleLine = true,
                supportingText = {
                    Text("Created in My Drive if it doesn't exist. The app can only see folders it created itself.")
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Trip") },
                supportingContent = {
                    Text(settings.tripFolder?.let { "Receipts go to the “$it” subfolder" }
                        ?: "Start a trip to put its receipts in their own subfolder")
                },
                trailingContent = {
                    if (settings.tripFolder != null) {
                        OutlinedButton(onClick = { scope.launch { repo.setTripFolder(null) } }) { Text("End trip") }
                    } else {
                        OutlinedButton(onClick = { showTripDialog = true }) { Text("Start trip") }
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Open scanner on launch") },
                supportingContent = { Text("Start in the camera every time the app opens") },
                trailingContent = {
                    Switch(settings.scanOnLaunch, onCheckedChange = { scope.launch { repo.setScanOnLaunch(it) } })
                },
            )
            ListItem(
                headlineContent = { Text("Upload without review") },
                supportingContent = { Text("Use the suggested name and go straight to the next scan") },
                trailingContent = {
                    Switch(settings.autoUpload, onCheckedChange = { scope.launch { repo.setAutoUpload(it) } })
                },
            )
        }
    }
}

/** Asks for the destination; month and year come from today, so a trip keeps one folder across a month end. */
@Composable
private fun StartTripDialog(onStart: (String) -> Unit, onDismiss: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf("") }
    val tripFolder = ReceiptNamer.tripFolderName(destination, LocalDate.now())
    val valid = ReceiptNamer.sanitize(destination).isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start trip") },
        text = {
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                supportingText = { if (valid) Text("Subfolder: $tripFolder") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onStart(tripFolder) }, enabled = valid) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
