package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.theme.LopBudgeTheme

@Composable
fun DeleteConfirmationDialog(
    choice: RecurringDeleteChoice?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.delete_confirm_title)
    val message = when (choice) {
        RecurringDeleteChoice.THIS_OCCURRENCE -> stringResource(R.string.delete_confirm_msg_single)
        RecurringDeleteChoice.FUTURE_ONLY -> stringResource(R.string.delete_confirm_msg_future)
        RecurringDeleteChoice.ALL_SERIES -> stringResource(R.string.delete_confirm_msg_all)
        null -> stringResource(R.string.delete_confirm_msg_normal)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag(TestTags.DELETE_CONFIRM_DIALOG),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag(TestTags.DELETE_CONFIRM_SUBMIT)
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.testTag(TestTags.DELETE_CONFIRM_CANCEL)
            ) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

@Preview
@Composable
fun DeleteConfirmationDialogNormalPreview() {
    LopBudgeTheme {
        DeleteConfirmationDialog(
            choice = null,
            onDismiss = {},
            onConfirm = {}
        )
    }
}

@Preview
@Composable
fun DeleteConfirmationDialogSeriesPreview() {
    LopBudgeTheme {
        DeleteConfirmationDialog(
            choice = RecurringDeleteChoice.ALL_SERIES,
            onDismiss = {},
            onConfirm = {}
        )
    }
}
