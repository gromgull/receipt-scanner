# Receipts

A small Android app that scans a receipt, crops and straightens it, reads it with OCR to name it, and uploads it to a Google Drive folder. Everything except the Drive upload runs on the phone.

## How it works

| Step | How |
|---|---|
| Camera, edge detection, crop, perspective fix | [ML Kit Document Scanner](https://developers.google.com/ml-kit/vision/doc-scanner): its own UI, provided by Play services, with no camera permission needed |
| OCR | [ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2) (Latin script; the model is bundled in the APK) |
| Naming | `scan/ReceiptNamer.kt`: plain-Kotlin rules that pick out the merchant, date and total. Example: `2026-11-04 - Rema 1000 - 64.80 NOK.jpg` |
| Drive auth | Google Identity `AuthorizationClient` with the `drive.file` scope |
| Upload | `WorkManager` job that calls the Drive v3 REST API through OkHttp; retries while offline |

The flow: the app opens straight into the scanner. After a scan you get a review screen with the suggested name, which you can edit, and an Upload button. In Settings you can change the Drive folder, connect Drive, and turn on **Upload without review**. With that on, you scan, it uploads, and the camera reopens for the next receipt.

### Why the folder is a name, not a folder picker

The app uses the `drive.file` scope, so it can only see files and folders it created itself. That scope is non-sensitive, so it needs no Google security review. The catch is that the app can't use an existing folder you made in Drive. Instead, it creates the folder named in Settings (default `Receipts`) in My Drive and reuses it after that.

## One-time Google Cloud setup

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and create a project, or pick an existing one.
2. Under **APIs & Services → Library**, enable the **Google Drive API**.
3. Under **APIs & Services → OAuth consent screen** (also called "Google Auth Platform"):
   - User type: **External**. Use **Internal** if everyone using it is in your Workspace.
   - Add the scope `.../auth/drive.file`.
   - While the app is in *Testing*, add your Google account under **Test users**.
4. Under **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `app.receiptscanner`
   - SHA-1: the fingerprint of the key that signs your build. For debug builds:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
     ```
     `./gradlew signingReport` shows the same thing. Add a second Android client for your release key if you make one.

No client ID or secret goes into the code. Google matches the app by its package name and signing certificate.

If you change `applicationId` in `app/build.gradle.kts`, change the OAuth client to match.

## Build & run

Open the folder in Android Studio and run, or:

```bash
./gradlew installDebug
```

```bash
./gradlew test
```

The phone needs Google Play services. The document scanner needs about 1.7 GB of RAM and downloads its model on first use.

## Ideas for later

- Save a PDF next to the JPEG: add `RESULT_FORMAT_PDF` to the scanner options.
- Tune `ReceiptNamer` against your own receipts. The tests in `app/src/test` are a good place to add real OCR output.
- Use a cloud model for naming if the rules aren't good enough. Plug it in where `ReceiptViewModel.onScanned` builds the `ReceiptInfo`.
