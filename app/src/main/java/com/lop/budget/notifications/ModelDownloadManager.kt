package com.lop.budget.notifications

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val modelFileName = "gemma-2b-it-gpu-int4.bin"
    private val modelUrl = "https://example.com/models/$modelFileName" // TODO: Mettre l'URL réelle ou laisser l'user la fournir

    fun isModelInstalled(): Boolean {
        val file = File(context.filesDir, modelFileName)
        return file.exists() && file.length() > 1_000_000_000 // > 1GB
    }

    fun startDownload(): Long {
        val request = DownloadManager.Request(Uri.parse(modelUrl))
            .setTitle("Téléchargement du modèle IA LOPBudge")
            .setDescription("Préparation de la détection intelligente (1.5 Go)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, modelFileName)
            .setAllowedOverMetered(false) // Wi-Fi seulement par défaut
            .setRequiresCharging(false)

        return downloadManager.enqueue(request)
    }

    fun getDownloadProgress(downloadId: Long): Flow<DownloadStatus> = flow {
        var isDownloading = true
        while (isDownloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    isDownloading = false
                    // Copier le fichier du dossier Download vers le dossier interne filesDir si nécessaire
                    finalizeDownload(downloadId)
                    emit(DownloadStatus.Success)
                } else if (status == DownloadManager.STATUS_FAILED) {
                    isDownloading = false
                    emit(DownloadStatus.Error("Échec du téléchargement"))
                } else {
                    val progress = if (bytesTotal > 0) (bytesDownloaded * 100L / bytesTotal).toInt() else 0
                    emit(DownloadStatus.Downloading(progress))
                }
            }
            cursor.close()
            if (isDownloading) delay(1000)
        }
    }

    private fun finalizeDownload(id: Long) {
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            val uriString = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
            val sourceFile = File(Uri.parse(uriString).path!!)
            val destFile = File(context.filesDir, modelFileName)
            
            if (sourceFile.exists()) {
                sourceFile.copyTo(destFile, overwrite = true)
                // Optionnel : supprimer le fichier source pour gagner de la place
                // sourceFile.delete()
            }
        }
        cursor.close()
    }

    sealed class DownloadStatus {
        data class Downloading(val progress: Int) : DownloadStatus()
        object Success : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
        object Idle : DownloadStatus()
    }
}
