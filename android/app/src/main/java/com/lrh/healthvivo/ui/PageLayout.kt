package com.lrh.healthvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// A full screen route keeps forms out of cramped system alert dialogs.
@Composable fun DetailPage(title: String, subtitle: String = "", dismiss: () -> Unit, canDismiss: Boolean = true,
    action: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if(canDismiss) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = dismiss, enabled = canDismiss) { AppIcon("left", "返回") }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        if(subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    action?.invoke()
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                if(footer != null) Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) { footer() }
                }
            }
        }
    }
}

@Composable fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable fun EmptyRecords(icon: String, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun AddButton(description: String, click: () -> Unit) {
    FilledIconButton(onClick = click, shape = MaterialTheme.shapes.small, modifier = Modifier.size(44.dp)) { AppIcon("plus", description, modifier = Modifier.size(24.dp)) }
}
