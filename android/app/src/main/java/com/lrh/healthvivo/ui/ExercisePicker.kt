package com.lrh.healthvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lrh.healthvivo.fitness.*

@Composable fun ExercisePicker(dismiss: () -> Unit, choose: (ExerciseType) -> Unit) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("全部") }
    val results = ExerciseLibrary.search(query, group.takeUnless { it == "全部" })
    DetailPage("选择运动", "选择与你的运动强度相符的条目", dismiss) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索名称，例如羽毛球、深蹲") }, leadingIcon = { AppIcon("search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            CategoryChips(listOf("全部") + ExerciseLibrary.groups, group) { group = it }
            Text("${results.size} 个类型 · 选择与你的强度相符的条目", style = MaterialTheme.typography.labelMedium)
            SelectionBox(results, key = { it.code }, resetKey = query to group, emptyText = "没有匹配条目，可返回填写名称并使用 AI。") { item ->
                LibraryRow(item.name, "${item.met} MET · ${item.group}", exerciseIcon(item.group)) { choose(item) }
            }
    }
}
