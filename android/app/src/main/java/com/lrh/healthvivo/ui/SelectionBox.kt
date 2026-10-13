package com.lrh.healthvivo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The bounded lazy list scrolls independently of its containing form.
@Composable fun <T> SelectionBox(values: List<T>, key: (T) -> String, resetKey: Any, emptyText: String,
    row: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(resetKey) { state.scrollToItem(0) }
    Surface(modifier = Modifier.fillMaxWidth().height(360.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            if(values.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(emptyText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(values, key = key) { row(it) }
            }
            if(values.isNotEmpty()) Text("上下滑动选择", modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 10.dp),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
