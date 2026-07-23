package com.lop.budget.notifications

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QwenDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val modelFileName = "qwen-0.5b-int4.onnx"
    private val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf" // Modèle libre Apache 2.0

    fun isModelInstalled(): Boolean {
        val file = File(context.filesDir, modelFileName)
        return file.exists() && file.length() > 100_000_000
    }

    fun startDownload(): Long {
        val request = DownloadManager.Request(Uri.parse(modelUrl))
            .setTitle("Moteur d'IA LOPBudge (Qwen)")
            .setDescription("Activation de la catégorisation intelligente (350 Mo)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, modelFileName)
            .setAllowedOverMetered(true) // Plus petit que Gemma, on peut autoriser le mobile si l'user veut

        return downloadManager.enqueue(request)
    }

    fun getDownloadProgress(downloadId: Long): Flow<DownloadStatus> = flow {
        var isDownloading = true
        while (isDownloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = try { downloadManager.query(query) } catch (e: Exception) { null }
            if (cursor != null && cursor.moveToFirst()) {
                val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                if (bytesDownloadedIdx != -1 && bytesTotalIdx != -1 && statusIdx != -1) {
                    val bytesDownloaded = cursor.getInt(bytesDownloadedIdx)
                    val bytesTotal = cursor.getInt(bytesTotalIdx)
                    val status = cursor.getInt(statusIdx)

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        isDownloading = false
                        finalizeDownload(downloadId)
                        emit(DownloadStatus.Success)
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                        emit(DownloadStatus.Error("Échec"))
                    } else {
                        val progress = if (bytesTotal > 0) (bytesDownloaded * 100L / bytesTotal).toInt() else 0
                        emit(DownloadStatus.Downloading(progress))
                    }
                }
            }
            cursor?.close()
            if (isDownloading) delay(1500)
        }
    }

    private fun finalizeDownload(id: Long) {
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = try { downloadManager.query(query) } catch (e: Exception) { null }
        if (cursor != null && cursor.moveToFirst()) {
            val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIdx != -1) {
                val uriString = cursor.getString(uriIdx)
                val path = Uri.parse(uriString).path
                if (path != null) {
                    val sourceFile = File(path)
                    val destFile = File(context.filesDir, modelFileName)
                    if (sourceFile.exists()) {
                        sourceFile.copyTo(destFile, overwrite = true)
                    }
                }
            }
        }
        cursor?.close()
    }

    sealed class DownloadStatus {
        data class Downloading(val progress: Int) : DownloadStatus()
        object Success : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
        object Idle : DownloadStatus()
    }
}
