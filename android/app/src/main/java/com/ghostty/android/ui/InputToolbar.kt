package com.ghostty.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Toolbar for terminal input with common keys and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputToolbar(
    onKeyPress: (String) -> Unit,
    onShowKeyboard: () -> Unit,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Common terminal keys
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolbarButton("ESC", onClick = { onKeyPress("\u001B") })
                ToolbarButton("TAB", onClick = { onKeyPress("\t") })
                // Control and Alt are sticky: a soft keyboard has no such keys,
                // so they apply to the next character typed.
                ToolbarButton("CTRL", onClick = onToggleCtrl, active = ctrlActive)
                ToolbarButton("ALT", onClick = onToggleAlt, active = altActive)
                ToolbarButton("↑", onClick = { onKeyPress("\u001B[A") })
                ToolbarButton("↓", onClick = { onKeyPress("\u001B[B") })
            }

            // Keyboard button
            FilledTonalIconButton(
                onClick = onShowKeyboard,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardAlt,
                    contentDescription = "Show keyboard"
                )
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val height = modifier.height(36.dp)
    val padding = PaddingValues(horizontal = 8.dp)

    // A sticky modifier that is armed has to look different from one that is not.
    if (active) {
        Button(onClick = onClick, modifier = height, contentPadding = padding) {
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = height, contentPadding = padding) {
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
