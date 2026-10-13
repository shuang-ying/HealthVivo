package com.lrh.healthvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI

@Composable fun ApiDialog(initial: ApiSettings, dismiss: () -> Unit, saving: Boolean, save: (ApiSettings) -> Unit, clear: () -> Unit,
    discovery: ModelDiscovery = remember { ModelDiscovery() }) {
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var secret by remember { mutableStateOf(initial.key) }
    var visibleKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var detecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("填写服务地址和密钥后自动检测模型") }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(endpoint, secret, retry) {
        models = emptyList()
        val connection = ApiSettings(endpoint.trim(), "", secret.trim())
        if(runCatching { connection.validateConnection() }.isFailure) {
            detecting = false; status = "填写 HTTPS 地址和密钥后自动检测模型"
            return@LaunchedEffect
        }
        detecting = true; status = "准备检测模型…"
        try {
            delay(1000)
            status = "正在获取模型列表…"
            val detected = discovery.discover(connection)
            models = detected
            if(model.isBlank() && detected.size == 1) model = detected.single()
            status = "检测到 ${detected.size} 个模型，请选择用于三餐和运动的模型"
        } catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) {
            status = when(failure) {
                is IllegalArgumentException -> failure.message ?: "无法检测模型，可手动填写"
                is java.io.IOException -> "模型检测连接失败或超时，可重试或手动填写"
                else -> "模型列表格式不兼容，可手动填写模型"
            }
        } finally { detecting = false }
    }
    EntryDialog("连接你的 AI", dismiss, {
        runCatching { ApiSettings(endpoint, model, secret).normalized().also { it.validate() } }.onSuccess(save).onFailure { error = it.message }
    }, saving, error, saveLabel = "保存配置") {
        Text("饮食识别、运动估算与每日建议，共用一个模型。", style = MaterialTheme.typography.bodyMedium)
        FormSection("连接服务") {
        OutlinedTextField(endpoint, { value ->
            // Changing hosts must not send a previously stored secret to a different provider.
            val before = runCatching { URI(endpoint.trim()).host }.getOrNull()
            val after = runCatching { URI(value.trim()).host }.getOrNull()
            if(before != after) secret = ""
            endpoint = value; error = null
        }, label = { Text("服务地址") }, placeholder = { Text("https://服务域名/v1") }, supportingText = { Text("可填基础地址或完整 chat/completions 地址") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), enabled = !saving, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it; error = null }, label = { Text("API 密钥") }, singleLine = true, enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = if(visibleKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { visibleKey = !visibleKey }) { AppIcon(if(visibleKey) "eye_off" else "eye", "显示或隐藏密钥") } }, modifier = Modifier.fillMaxWidth())
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(detecting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(status, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { retry++ }, enabled = !saving && !detecting && runCatching { ApiSettings(endpoint, "", secret).validateConnection() }.isSuccess) { Text("重新检测") }
                    if(models.isNotEmpty()) TextButton(onClick = { picker = true }, enabled = !saving) { Text("选择模型") }
                }
            }
        }
        FormSection("使用的模型") {
        OutlinedTextField(model, { model = it; error = null }, label = { Text("模型名称") }, singleLine = true, enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            supportingText = { Text("可选择检测结果，也可手动填写") }, modifier = Modifier.fillMaxWidth())
        }
        Text("模型检测只读取列表，不发送健康记录或调用模型生成。列表不保证模型支持图片，请选择服务商注明支持视觉输入的模型用于拍照识别。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("密钥在本机加密保存。食物仅发送本次描述/照片；运动仅发送本次运动和填写的体重；每日建议会在保存健康记录后自动发送资料、当天记录和近7日体重摘要，也可手动重新生成。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = clear, enabled = !saving) { Text("清除配置") }
    }
    if(picker) ModelPicker(models, model, { picker = false }) { selected -> model = selected; picker = false }
}

@Composable private fun ModelPicker(models: List<String>, selected: String, dismiss: () -> Unit, choose: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val filtered = models.filter { it.contains(query, ignoreCase = true) }
    DetailPage("选择模型", "选择用于饮食识别与运动估算的模型", dismiss) {
            OutlinedTextField(query, { query = it; page = 0 }, label = { Text("搜索模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("${filtered.size} 个结果", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filtered.drop(page * 12).take(12).forEach { id ->
                    LibraryRow(id, if(selected == id) "当前选择" else "点击选择", if(selected == id) "check" else "sparkles") { choose(id) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                TextButton(onClick = { page++ }, enabled = (page + 1) * 12 < filtered.size) { Text("下一页") }
            }
    }
}
