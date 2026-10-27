package com.onlasdan.studio.util

import com.onlasdan.studio.Constants

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.onlasdan.studio.ui.MainActivity
import com.onlasdan.studio.ui.TxdEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable

enum class LauncherFileType {
    IMG,
    TXD,
    FOLDER
}

data class ParentArchiveInfo(
    val archivePath: String,
    val entryName: String,
    val originalRestrictedPath: String? = null,
    val isShizuku: Boolean = false
) : Serializable

object WindowLauncher {

    const val EXTRA_FILE_PATH = Constants.IntentExtra.FILE_PATH
    const val EXTRA_IS_SHIZUKU = Constants.IntentExtra.IS_SHIZUKU
    const val EXTRA_PARENT_ARCHIVE_PATH = Constants.IntentExtra.PARENT_ARCHIVE_PATH
    const val EXTRA_PARENT_ENTRY_NAME = Constants.IntentExtra.PARENT_ENTRY_NAME
    const val EXTRA_PARENT_RESTRICTED_PATH = Constants.IntentExtra.PARENT_RESTRICTED_PATH
    const val EXTRA_PARENT_IS_SHIZUKU = Constants.IntentExtra.PARENT_IS_SHIZUKU

    fun updateTaskDescription(activity: Activity, title: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setTaskDescription(
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(title)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                activity.setTaskDescription(ActivityManager.TaskDescription(title))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun open(
        context: Context,
        uri: Uri? = null,
        filePath: String? = null,
        fileType: LauncherFileType,
        isShizuku: Boolean = false,
        parentArchiveInfo: ParentArchiveInfo? = null,
        onSingleWindowAction: (() -> Unit)? = null
    ) {
        if (filePath != null && parentArchiveInfo == null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                AppSettings.addRecentFile(context, filePath, fileType, isShizuku)
            }
        }

        // Folders (e.g. texdb caches) are recorded in recents only; reopening
        // them is handled by the caller, there is no window to launch.
        if (fileType == LauncherFileType.FOLDER) return

        val windowMode = AppSettings.cachedWindowMode

        // 1. SINGLE_WINDOW mode: keep inside same window if in-app action provided
        if (windowMode == WindowMode.SINGLE_WINDOW && onSingleWindowAction != null) {
            onSingleWindowAction()
            return
        }

        val targetClass = when (fileType) {
            LauncherFileType.IMG -> MainActivity::class.java
            LauncherFileType.TXD -> TxdEditorActivity::class.java
            LauncherFileType.FOLDER -> MainActivity::class.java // unreachable, guarded above
        }

        val intent = Intent(context, targetClass).apply {
            if (uri != null) data = uri
            if (filePath != null) putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_IS_SHIZUKU, isShizuku)

            if (parentArchiveInfo != null) {
                putExtra(EXTRA_PARENT_ARCHIVE_PATH, parentArchiveInfo.archivePath)
                putExtra(EXTRA_PARENT_ENTRY_NAME, parentArchiveInfo.entryName)
                putExtra(EXTRA_PARENT_RESTRICTED_PATH, parentArchiveInfo.originalRestrictedPath)
                putExtra(EXTRA_PARENT_IS_SHIZUKU, parentArchiveInfo.isShizuku)
            }
        }

        when (windowMode) {
            WindowMode.PER_FILE -> {
                // Check if this file is already open in an existing task
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    for (task in am.appTasks) {
                        val baseIntent = task.taskInfo.baseIntent
                        val taskData = baseIntent?.data
                        val taskPath = baseIntent?.getStringExtra(EXTRA_FILE_PATH)
                        val sameUri = (uri != null && taskData != null && taskData == uri)
                        val samePath = (filePath != null && taskPath != null && taskPath == filePath)

                        if (sameUri || samePath) {
                            task.moveToFront()
                            return
                        }
                    }
                }

                // Document task: multiple distinct cards in Recent Apps
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                context.startActivity(intent)
            }

            WindowMode.PER_FEATURE -> {
                // Separate tasks for IMG and TXD (singleTask + distinct taskAffinity)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            WindowMode.SINGLE_WINDOW -> {
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
