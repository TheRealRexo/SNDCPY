package com.SNDCPY.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * APK self-update helper.
 *
 * Flow: user picks a local APK file (ACTION_OPEN_DOCUMENT) → the APK is
 * copied into the app's cache directory → a FileProvider content URI is
 * built → the system package installer is launched via ACTION_VIEW.
 *
 * Requires the `REQUEST_INSTALL_PACKAGES` permission; on Android 11+ the
 * user must grant "install unknown apps" for this app before the install
 * prompt appears.
 */
object SelfUpdater {

    fun isUnknownSourcesGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens Settings → "Install unknown apps" page for this app. */
    fun openInstallUnknownAppsSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        )
        ctx.startActivity(intent)
    }

    /**
     * Copies [sourceUri] into the cache and launches the system installer.
     * Returns false if the copy failed.
     */
    fun installFromUri(ctx: Context, sourceUri: Uri): Boolean {
        val cache = File(ctx.cacheDir, "updates")
        cache.mkdirs()
        val apkName = "SNDCPY-update.apk"
        val target = File(cache, apkName)
        try {
            ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            } ?: run {
                return false
            }
            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                target
            )
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(install)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /** Convenience check + fix: ensures the user can install packages. */
    fun ensureInstallPermission(ctx: Context, onGranted: () -> Unit, onDenied: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isUnknownSourcesGranted(ctx)) {
            onGranted()
        } else {
            openInstallUnknownAppsSettings(ctx)
            onDenied()
        }
    }
}