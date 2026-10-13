package com.lrh.healthvivo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.R

val accent = Color(0xFF5955E7)
@Composable fun VitalityTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val colors = if(darkOverride ?: isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFBCBAFF), onPrimary = Color(0xFF242162), primaryContainer = Color(0xFF333064), onPrimaryContainer = Color(0xFFE7E5FF),
        secondaryContainer = Color(0xFF252536), onSecondaryContainer = Color(0xFFD3D2E7), tertiary = Color(0xFFFFBA94),
        background = Color(0xFF111119), onBackground = Color(0xFFF0EFF7), surface = Color(0xFF1B1B27),
        onSurface = Color(0xFFF0EFF7), onSurfaceVariant = Color(0xFFAAA9BE), surfaceVariant = Color(0xFF252535), outline = Color(0xFF76748B), outlineVariant = Color(0xFF333344)
    ) else lightColorScheme(primary = accent, onPrimary = Color.White, primaryContainer = Color(0xFFEEEDFF), onPrimaryContainer = Color(0xFF37328A),
        secondary = Color(0xFF646278), secondaryContainer = Color(0xFFF0F0F8), onSecondaryContainer = Color(0xFF55516F), tertiary = Color(0xFFC76A3B),
        background = Color(0xFFF6F7FB), onBackground = Color(0xFF20202E), surface = Color.White, onSurface = Color(0xFF20202E),
        onSurfaceVariant = Color(0xFF75758B), surfaceVariant = Color(0xFFF2F3F8), outline = Color(0xFFBABACA), outlineVariant = Color(0xFFE8E9F1))
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)), content = content)
}

@Composable fun AppIcon(name: String, description: String? = null, modifier: Modifier = Modifier.size(22.dp), tint: Color = LocalContentColor.current) {
    val id = when(name) {
        "home" -> R.drawable.ic_home; "food" -> R.drawable.ic_food; "dumbbell" -> R.drawable.ic_dumbbell; "chart" -> R.drawable.ic_chart; "user" -> R.drawable.ic_user
        "steps" -> R.drawable.ic_steps; "scale" -> R.drawable.ic_scale; "sparkles" -> R.drawable.ic_sparkles; "camera" -> R.drawable.ic_camera; "image" -> R.drawable.ic_image
        "plus" -> R.drawable.ic_plus; "search" -> R.drawable.ic_search; "left" -> R.drawable.ic_left; "right" -> R.drawable.ic_right; "trash" -> R.drawable.ic_trash
        "edit" -> R.drawable.ic_edit; "download" -> R.drawable.ic_download; "upload" -> R.drawable.ic_upload; "settings" -> R.drawable.ic_settings; "book" -> R.drawable.ic_book
        "drag" -> R.drawable.ic_drag
        "check" -> R.drawable.ic_check; "eye" -> R.drawable.ic_eye; "eye_off" -> R.drawable.ic_eye_off; "flame" -> R.drawable.ic_flame; "clock" -> R.drawable.ic_clock
        "route" -> R.drawable.ic_route; "layers" -> R.drawable.ic_layers; "close" -> R.drawable.ic_close; "leaf" -> R.drawable.ic_leaf; "bike" -> R.drawable.ic_bike
        "water" -> R.drawable.ic_water; "ball" -> R.drawable.ic_ball; "apple" -> R.drawable.ic_apple; "nut" -> R.drawable.ic_nut; "cup" -> R.drawable.ic_cup
        "bowl" -> R.drawable.ic_bowl; "milk" -> R.drawable.ic_milk; "mountain" -> R.drawable.ic_mountain; "snow" -> R.drawable.ic_snow; "shield" -> R.drawable.ic_shield
        else -> R.drawable.ic_sun
    }
    Icon(painterResource(id), description, modifier, tint)
}
fun foodIcon(category: String) = when(category) { "主食" -> "bowl"; "肉蛋鱼" -> "food"; "乳制品" -> "milk"; "水果" -> "apple"; "坚果", "豆类" -> "nut"; "饮品" -> "cup"; "蔬菜" -> "leaf"; else -> "food" }
fun exerciseIcon(group: String) = when(group) { "步行跑步" -> "steps"; "骑行" -> "bike"; "游泳" -> "water"; "球类运动" -> "ball"; "冰雪运动" -> "snow"; "舞蹈" -> "sparkles"; else -> "dumbbell" }

@Composable fun CategoryChips(categories: List<String>, selected: String, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { label -> FilterChip(selected == label, { choose(label) }, label = { Text(label, fontSize = 12.sp) }, shape = RoundedCornerShape(50),
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == label, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary)) }
    }
}

@Composable fun IconBadge(name: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Surface(modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = if(accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.padding(10.dp)) { AppIcon(name, modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary) }
    }
}
@Composable fun LibraryRow(name: String, subtitle: String, icon: String, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconBadge(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon("plus", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
