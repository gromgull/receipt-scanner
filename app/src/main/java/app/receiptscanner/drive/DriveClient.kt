package app.receiptscanner.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

/** Minimal Drive v3 REST client — just what we need, without the heavy Java client library. */
class DriveClient(private val accessToken: String) {

    /** Returns the id of the folder called [name] directly inside [parentId], creating it if missing. */
    suspend fun findOrCreateFolder(name: String, parentId: String = ROOT): String = withContext(Dispatchers.IO) {
        findFolder(name, parentId) ?: createFolder(name, parentId)
    }

    /** Asks Drive for a file id to create a file with later; see [upload]. */
    suspend fun generateFileId(): String = withContext(Dispatchers.IO) {
        val url = "$FILES_URL/generateIds?count=1&fields=ids"
        JSONObject(execute(Request.Builder().url(url).get().build())).getJSONArray("ids").getString(0)
    }

    /**
     * Uploads [file] as [fileName] into [folderId], creating it with the id [fileId].
     *
     * Safe to repeat with the same [fileId]: if an earlier attempt reached Drive even though
     * we never saw the response, Drive answers 409 instead of creating a second file.
     */
    suspend fun upload(
        file: File,
        fileName: String,
        folderId: String,
        fileId: String,
        mimeType: String = "image/jpeg",
    ): Unit =
        withContext(Dispatchers.IO) {
            val metadata = JSONObject()
                .put("id", fileId)
                .put("name", fileName)
                .put("parents", JSONArray().put(folderId))
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody(JSON))
                .addPart(file.asRequestBody(mimeType.toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$UPLOAD_URL?uploadType=multipart&fields=id")
                .post(body)
                .build()
            try {
                execute(request)
            } catch (e: DriveException) {
                if (e.code != HTTP_CONFLICT) throw e
            }
        }

    private fun findFolder(name: String, parentId: String): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val url = FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter(
                "q",
                "name = '$escaped' and mimeType = '$FOLDER_MIME' and '$parentId' in parents and trashed = false",
            )
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val files = JSONObject(execute(Request.Builder().url(url).get().build())).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFolder(name: String, parentId: String): String {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
        val request = Request.Builder()
            .url("$FILES_URL?fields=id")
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        return JSONObject(execute(request)).getString("id")
    }

    private fun execute(request: Request): String {
        val authed = request.newBuilder().header("Authorization", "Bearer $accessToken").build()
        http.newCall(authed).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveException(response.code, body.take(500))
            return body
        }
    }

    companion object {
        private const val ROOT = "root"
        private const val HTTP_CONFLICT = 409
        private const val FILES_URL ="https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
