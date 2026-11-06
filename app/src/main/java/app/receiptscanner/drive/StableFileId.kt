package app.receiptscanner.drive

import java.io.File

/**
 * Returns the Drive file id saved in [idFile], or saves and returns a new one from [generate].
 *
 * Every attempt to upload a receipt has to use the same id, or a retry after a lost response
 * creates a duplicate. The id is written before the first upload and via a rename, so a crash
 * can't leave a partial id behind.
 */
internal suspend fun stableFileId(idFile: File, generate: suspend () -> String): String {
    if (idFile.exists()) idFile.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
    val id = generate()
    val temp = File(idFile.parentFile, "${idFile.name}.tmp")
    temp.writeText(id)
    if (!temp.renameTo(idFile)) throw java.io.IOException("Could not save Drive file id")
    return id
}
