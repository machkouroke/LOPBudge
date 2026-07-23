package com.lop.budget.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lop.budget.notifications.ModelDownloadManager

@Composable
fun ModelDownloadDialog(
    status: ModelDownloadManager.DownloadStatus,
    isInstalled: Boolean,
    onStartDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modèle IA Local") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isInstalled) {
                    Text("Le modèle Gemma 2b est déjà installé et prêt à l'emploi.")
                } else {
                    Text("Pour activer la détection avancée, vous devez télécharger le modèle Gemma 2b (environ 1.5 Go).")
                    
                    when (status) {
                        is ModelDownloadManager.DownloadStatus.Downloading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LinearProgressIndicator(
                                    progress = status.progress / 100f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("${status.progress}%", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is ModelDownloadManager.DownloadStatus.Error -> {
                            Text("Erreur : ${status.message}", color = MaterialTheme.colorScheme.error)
                        }
                        ModelDownloadManager.DownloadStatus.Success -> {
                            Text("Téléchargement réussi ! Le modèle est prêt.", color = com.lop.budget.ui.theme.IncomeGreen)
                        }
                        else -> {
                            Text("Il est recommandé de télécharger via une connexion Wi-Fi.")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isInstalled && status !is ModelDownloadManager.DownloadStatus.Downloading) {
                Button(onClick = onStartDownload) {
                    Text("Télécharger")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Fermer")
                }
            }
        },
        dismissButton = {
            if (!isInstalled && status !is ModelDownloadManager.DownloadStatus.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text("Annuler")
                }
            }
        }
    )
}
