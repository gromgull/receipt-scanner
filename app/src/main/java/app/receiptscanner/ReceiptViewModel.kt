package app.receiptscanner

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.receiptscanner.scan.ReceiptInfo
import app.receiptscanner.scan.ReceiptNamer
import app.receiptscanner.scan.ReceiptOcr
import app.receiptscanner.settings.Settings
import app.receiptscanner.settings.SettingsRepository
import app.receiptscanner.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

sealed interface ScanState {
    data object Idle : ScanState
    data object Processing : ScanState
    data class Review(val image: File, val suggestedName: String, val info: ReceiptInfo) : ScanState
}

data class UploadItem(
    val id: String,
    val path: String,
    val name: String,
    val time: Long,
    val state: WorkInfo.State,
    val error: String?,
    val tripFolder: String?,
)

class ReceiptViewModel(app: Application) : AndroidViewModel(app) {

    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<Settings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uploads: StateFlow<List<UploadItem>> =
        WorkManager.getInstance(app).getWorkInfosByTagFlow(UploadWorker.WORK_TAG)
            .map { infos ->
                infos.mapNotNull { it.toUploadItem() }
                    .groupBy { it.path }.values.map { sameFile -> sameFile.maxBy { it.time } }
                    .sortedByDescending { it.time }
                    .take(50)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called with the cropped/straightened JPEG from the ML Kit document scanner. */
    fun onScanned(uri: Uri) {
        _scan.value = ScanState.Processing
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "scans").apply { mkdirs() }
                File(dir, "scan-${System.currentTimeMillis()}.jpg").also { out ->
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            val today = LocalDate.now()
            val info = try {
                ReceiptNamer.extract(ReceiptOcr.readLines(ctx, Uri.fromFile(file)), today)
            } catch (e: Exception) {
                Log.w("ReceiptViewModel", "OCR failed", e)
                ReceiptInfo(null, null, null, null)
            }
            _scan.value = ScanState.Review(file, ReceiptNamer.suggestName(info, today), info)
        }
    }

    /** Queue the reviewed receipt for upload. Caller must have ensured Drive authorization. */
    fun upload(name: String) {
        val review = _scan.value as? ScanState.Review ?: return
        val folder = settings.value?.folderName ?: Settings.DEFAULT_FOLDER
        val trip = settings.value?.tripFolder
        val fileName = ReceiptNamer.sanitize(name).ifEmpty { review.suggestedName }
        UploadWorker.enqueue(getApplication(), review.image, fileName, folder, trip)
        _scan.value = ScanState.Idle
        _message.value = "Uploading “$fileName” to Drive/$folder" + trip?.let { "/$it" }.orEmpty()
    }

    fun discard() {
        (_scan.value as? ScanState.Review)?.image?.delete()
        _scan.value = ScanState.Idle
    }

    fun retry(item: UploadItem) {
        val folder = settings.value?.folderName ?: Settings.DEFAULT_FOLDER
        UploadWorker.schedule(getApplication(), item.path, folder, item.tripFolder)
    }

    fun onDriveConnected() = viewModelScope.launch { settingsRepo.setDriveConnected(true) }
    fun showMessage(text: String) { _message.value = text }
    fun messageShown() { _message.value = null }

    private fun WorkInfo.toUploadItem(): UploadItem? {
        val path = tags.firstOrNull { it.startsWith(UploadWorker.PATH_TAG_PREFIX) }
            ?.removePrefix(UploadWorker.PATH_TAG_PREFIX) ?: return null
        val time = tags.firstOrNull { it.startsWith(UploadWorker.TIME_TAG_PREFIX) }
            ?.removePrefix(UploadWorker.TIME_TAG_PREFIX)?.toLongOrNull() ?: 0L
        return UploadItem(
            id = id.toString(),
            path = path,
            name = File(path).name.removeSuffix(".jpg"),
            time = time,
            state = state,
            error = outputData.getString(UploadWorker.KEY_ERROR),
            tripFolder = tags.firstOrNull { it.startsWith(UploadWorker.TRIP_TAG_PREFIX) }
                ?.removePrefix(UploadWorker.TRIP_TAG_PREFIX),
        )
    }
}
