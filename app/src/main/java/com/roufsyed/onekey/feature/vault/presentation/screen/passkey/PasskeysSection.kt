package com.roufsyed.onekey.feature.vault.presentation.screen.passkey

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.model.PasskeySummary

/**
 * Read-only list of the passkeys attached to a credential, in view mode of the
 * detail screen: one card per passkey with the full record laid out line by
 * line. Rows and the delete confirmation are shared with the Passkeys tab
 * ([PasskeyRow], [DeletePasskeyDialog]).
 */
@Composable
internal fun PasskeysSection(
    passkeys: List<PasskeySummary>,
    onDelete: (String) -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "Passkeys (${passkeys.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        passkeys.forEach { passkey ->
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PasskeyRow(
                    passkey = passkey,
                    onDelete = { pendingDelete = passkey.id },
                    detailed = true,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 16.dp),
                )
            }
        }
    }

    pendingDelete?.let { id ->
        DeletePasskeyDialog(
            target = passkeys.firstOrNull { it.id == id },
            onConfirm = {
                pendingDelete = null
                onDelete(id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
