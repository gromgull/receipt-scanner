package app.receiptscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import app.receiptscanner.ReceiptViewModel
import app.receiptscanner.ScanState
import app.receiptscanner.UploadItem
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

@Composable
fun ReceiptApp(
    viewModel: ReceiptViewModel,
    onScan: () -> Unit,
    onUpload: (String) -> Unit,
    onConnectDrive: () -> Unit,
) {
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            viewModel.messageShown()
            snackbar.showSnackbar(it)
        }
    }

    val current = settings
    when {
        showSettings && current != null -> SettingsScreen(
            settings = current,
            repo = viewModel.settingsRepo,
            onConnectDrive = onConnectDrive,
            onBack = { showSettings = false },
        )
        scan is ScanState.Processing -> Processing()
        scan is ScanState.Review -> {
            val review = scan as ScanState.Review
            ReviewScreen(
                review = review,
                folderName = current?.folderName.orEmpty(),
                autoUpload = current?.autoUpload == true,
                snackbar = snackbar,
                onUpload = onUpload,
                onRetake = { viewModel.discard(); onScan() },
                onDiscard = viewModel::discard,
            )
        }
        else -> HomeScreen(
            uploads = uploads,
            snackbar = snackbar,
            onScan = onScan,
            onSettings = { showSettings = true },
            onRetry = viewModel::retry,
        )
    }
}

@Composable
private fun Processing() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Reading receipt…")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uploads: List<UploadItem>,
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRetry: (UploadItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.DocumentScanner, null) },
                text = { Text("Scan receipt") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (uploads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No receipts yet. Tap “Scan receipt” to photograph one.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(uploads, key = { it.id }) { item -> UploadRow(item, onRetry) }
            }
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem, onRetry: (UploadItem) -> Unit) {
    val (icon, status) = when (item.state) {
        WorkInfo.State.SUCCEEDED -> Icons.Filled.CheckCircle to "Uploaded"
        WorkInfo.State.RUNNING -> Icons.Filled.CloudUpload to "Uploading…"
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Icons.Filled.Error to (item.error ?: "Failed")
        else -> Icons.Filled.Schedule to "Waiting for network"
    }
    val failed = item.state == WorkInfo.State.FAILED || item.state == WorkInfo.State.CANCELLED
    ListItem(
        leadingContent = {
            Icon(icon, null, tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val time = if (item.time > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(item.time)) + " · " else ""
            Text(time + status, maxLines = 2)
        },
        trailingContent = {
            if (failed) TextButton(onClick = { onRetry(item) }) { Text("Retry") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReviewScreen(
    review: ScanState.Review,
    folderName: String,
    autoUpload: Boolean,
    snackbar: SnackbarHostState,
    onUpload: (String) -> Unit,
    onRetake: () -> Unit,
    onDiscard: () -> Unit,
) {
    var name by rememberSaveable(review.image.path) { mutableStateOf(review.suggestedName) }
    BackHandler(onBack = onDiscard)

    // Auto-upload mode: no review needed, ship it with the suggested name.
    LaunchedEffect(review.image.path, autoUpload) {
        if (autoUpload) onUpload(review.suggestedName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onDiscard) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Discard") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = review.image,
                contentDescription = "Scanned receipt",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
            // Wrap chips onto new lines instead of squeezing them (long merchant names, large fonts).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                val detected = listOfNotNull(
                    review.info.merchant,
                    review.info.date?.toString(),
                    review.info.total?.let { listOfNotNull(it, review.info.currency).joinToString(" ") },
                )
                detected.forEach { value ->
                    AssistChip(
                        onClick = {},
                        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace("\n", " ") },
                label = { Text("File name") },
                // Wraps instead of scrolling sideways so the whole name stays visible.
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                supportingText = { Text("Saved as .jpg in Drive › $folderName") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake") }
                Button(onClick = { onUpload(name) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Upload")
                }
            }
        }
    }
}
