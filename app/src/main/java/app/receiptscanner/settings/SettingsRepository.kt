package app.receiptscanner.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    /** Drive folder (in My Drive root) that receipts are uploaded to. Created by the app if missing. */
    val folderName: String = DEFAULT_FOLDER,
    /** Skip the review screen and upload immediately with the suggested name. */
    val autoUpload: Boolean = false,
    /** Open the scanner straight away when the app starts. */
    val scanOnLaunch: Boolean = true,
    /** Set once the user has granted Drive access. */
    val driveConnected: Boolean = false,
) {
    companion object {
        const val DEFAULT_FOLDER = "Receipts"
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val folder = stringPreferencesKey("folder_name")
        val autoUpload = booleanPreferencesKey("auto_upload")
        val scanOnLaunch = booleanPreferencesKey("scan_on_launch")
        val driveConnected = booleanPreferencesKey("drive_connected")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setFolderName(name: String) = update { it[Keys.folder] = name }
    suspend fun setAutoUpload(value: Boolean) = update { it[Keys.autoUpload] = value }
    suspend fun setScanOnLaunch(value: Boolean) = update { it[Keys.scanOnLaunch] = value }
    suspend fun setDriveConnected(value: Boolean) = update { it[Keys.driveConnected] = value }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    private fun Preferences.toSettings() = Settings(
        folderName = this[Keys.folder]?.takeIf { it.isNotBlank() } ?: Settings.DEFAULT_FOLDER,
        autoUpload = this[Keys.autoUpload] ?: false,
        scanOnLaunch = this[Keys.scanOnLaunch] ?: true,
        driveConnected = this[Keys.driveConnected] ?: false,
    )
}
