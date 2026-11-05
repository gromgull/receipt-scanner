package app.receiptscanner.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.receiptscanner.drive.DriveAuth
import app.receiptscanner.drive.DriveClient
import app.receiptscanner.drive.DriveException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads one receipt to Drive. Runs under WorkManager so it survives offline periods,
 * app restarts and reboots. The local copy is deleted only after a successful upload.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val file = inputData.getString(KEY_PATH)?.let(::File)
        if (file == null || !file.exists()) return Result.failure(error("Local file is missing"))
        val folderName = inputData.getString(KEY_FOLDER) ?: return Result.failure(error("No folder"))

        val auth = try {
            DriveAuth.authorize(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Authorization failed", e)
            return retryOrFail("Google authorization failed")
        }
        if (auth.hasResolution()) {
            // Access was never granted or was revoked; needs UI. User can retry from the app.
            return Result.failure(error("Connect Google Drive in Settings, then retry"))
        }
        val token = auth.accessToken ?: return retryOrFail("No access token")

        return try {
            val drive = DriveClient(token)
            // Serialize folder lookup so parallel uploads don't create duplicate folders.
            val folderId = folderMutex.withLock { drive.findOrCreateFolder(folderName) }
            drive.upload(file, file.name, folderId)
            file.parentFile?.deleteRecursively()
            Result.success()
        } catch (e: DriveException) {
            Log.w(TAG, "Drive error", e)
            if (e.code in RETRYABLE_HTTP || e.code >= 500) retryOrFail("Drive error ${e.code}")
            else Result.failure(error("Drive rejected the upload (${e.code})"))
        } catch (e: IOException) {
            Log.w(TAG, "Network error", e)
            retryOrFail("Network error")
        }
    }

    private fun retryOrFail(message: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(error(message))

    private fun error(message: String) = workDataOf(KEY_ERROR to message)

    companion object {
        private const val TAG = "UploadWorker"
        private const val KEY_PATH = "path"
        private const val KEY_FOLDER = "folder"
        const val KEY_ERROR = "error"
        const val WORK_TAG = "receipt-upload"
        const val PATH_TAG_PREFIX = "path:"
        const val TIME_TAG_PREFIX = "time:"
        private const val MAX_ATTEMPTS = 10
        private val RETRYABLE_HTTP = setOf(401, 403, 408, 429)
        private val folderMutex = Mutex()

        /**
         * Moves [scan] into a private outbox as "[fileName].jpg" and schedules the upload.
         * Each receipt gets its own outbox directory, so identical names never collide.
         */
        fun enqueue(context: Context, scan: File, fileName: String, folderName: String) {
            val dir = File(context.filesDir, "outbox/${UUID.randomUUID()}").apply { mkdirs() }
            val target = File(dir, "$fileName.jpg")
            if (!scan.renameTo(target)) {
                scan.copyTo(target, overwrite = true)
                scan.delete()
            }
            schedule(context, target.absolutePath, folderName)
        }

        /**
         * (Re)schedules the upload of a file in the outbox. Work is unique per file, so
         * retrying a failed upload replaces its old entry instead of adding a second one.
         */
        fun schedule(context: Context, path: String, folderName: String) {
            if (!File(path).exists()) return
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_PATH to path, KEY_FOLDER to folderName))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .addTag(PATH_TAG_PREFIX + path)
                .addTag(TIME_TAG_PREFIX + System.currentTimeMillis())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(path, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
