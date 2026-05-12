package com.explo.capstone.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

import android.util.Log

object PackageUtils {
    private const val TAG = "PackageUtils"

    /**
     * Attempts to uninstall the app.
     * Tries ACTION_DELETE first, then falls back to App Settings.
     */
    fun uninstallApp(context: Context) {
        Log.d(TAG, "uninstallApp called for ${context.packageName}")
        val packageName = context.packageName
        
        // 1. Try direct uninstall dialog
        try {
            Log.d(TAG, "Attempting ACTION_DELETE with Uri.fromParts")
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "ACTION_DELETE started successfully")
            // return // FOR TESTING: LET IT FALL THROUGH TO SETTINGS TOO
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_DELETE failed", e)
        }

        // 2. Fallback to App Info page
        try {
            Log.d(TAG, "Attempting ACTION_APPLICATION_DETAILS_SETTINGS with Uri.fromParts")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS started successfully")
            return
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS failed", e)
        }

        // 3. Final fallback to Manage Applications list
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Total failure - nothing else we can do
        }
    }
}
