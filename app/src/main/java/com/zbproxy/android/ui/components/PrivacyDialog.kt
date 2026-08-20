package com.zbproxy.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zbproxy.android.R

/**
 * Privacy Policy + AI-Built notice dialog shown on first launch.
 * User can agree or decline (decline keeps app usable, dialog won't show again).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit = onDecline
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Column {
                Text(
                    stringResource(R.string.privacy_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.privacy_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp)
            ) {
                // Privacy Policy section
                SectionHeader(
                    icon = Icons.Filled.Lock,
                    text = stringResource(R.string.privacy_section_privacy)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                // AI Notice section
                SectionHeader(
                    icon = Icons.Filled.SmartToy,
                    text = stringResource(R.string.privacy_section_ai)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_ai_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onAgree) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.privacy_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(
                    stringResource(R.string.privacy_decline),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}