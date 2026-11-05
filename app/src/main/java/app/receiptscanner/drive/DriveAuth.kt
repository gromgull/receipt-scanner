package app.receiptscanner.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Drive authorization via Google Identity Services' AuthorizationClient.
 *
 * Uses the `drive.file` scope: the app can only see files/folders it created itself.
 * That's non-sensitive (no Google verification needed) and is all we need, because the
 * app creates the receipts folder itself.
 *
 * No client ID is needed in code: Google matches the app by package name + signing
 * certificate SHA-1 against an "Android" OAuth client in your Cloud project (see README).
 */
object DriveAuth {
    private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
            .build()

    /**
     * Returns a result that either carries an access token, or (first time / revoked)
     * `hasResolution() == true` with a PendingIntent that must be launched from an Activity.
     * Safe to call from background work: it never shows UI by itself.
     */
    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request).await()
}
