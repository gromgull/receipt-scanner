package app.receiptscanner

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.receiptscanner.drive.DriveAuth
import app.receiptscanner.ui.ReceiptApp
import app.receiptscanner.ui.theme.ReceiptTheme
import com.google.android.gms.auth.api.identity.Identity
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ReceiptViewModel by viewModels()

    /**
     * ML Kit Document Scanner: live edge detection, auto-capture, perspective correction,
     * manual crop adjust, rotate and cleanup filters — all on-device via Play services.
     */
    private val scanner: GmsDocumentScanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setPageLimit(1)
                .setGalleryImportAllowed(true)
                .build()
        )
    }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val page = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()
            page?.imageUri?.let(viewModel::onScanned)
        }

    /** Runs after the user completes the Google "allow Drive access" consent screen. */
    private var afterAuthorization: (() -> Unit)? = null

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = afterAuthorization
            afterAuthorization = null
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.showMessage("Google Drive access was not granted")
                return@registerForActivityResult
            }
            try {
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                viewModel.onDriveConnected()
                action?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Authorization failed", e)
                viewModel.showMessage("Google Drive authorization failed")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReceiptTheme {
                ReceiptApp(
                    viewModel = viewModel,
                    onScan = ::launchScanner,
                    onUpload = { name -> withDriveAccess { viewModel.upload(name); maybeScanAgain() } },
                    onConnectDrive = { withDriveAccess { viewModel.showMessage("Google Drive connected") } },
                )
            }
        }
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val settings = viewModel.settings.filterNotNull().first()
                if (settings.scanOnLaunch) launchScanner()
            }
        }
    }

    fun launchScanner() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Scanner unavailable", e)
                viewModel.showMessage("Scanner unavailable: ${e.message}")
            }
    }

    /** In auto-upload mode, go straight back to the camera for the next receipt. */
    private fun maybeScanAgain() {
        if (viewModel.settings.value?.autoUpload == true) launchScanner()
    }

    /** Ensures Drive access is granted (showing Google's consent UI if needed), then runs [action]. */
    private fun withDriveAccess(action: () -> Unit) {
        lifecycleScope.launch {
            try {
                val result = DriveAuth.authorize(this@MainActivity)
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    afterAuthorization = action
                    authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    viewModel.onDriveConnected()
                    action()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Authorization failed", e)
                viewModel.showMessage("Google sign-in failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
