package com.lrh.healthvivo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import com.lrh.healthvivo.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

open class MainActivity : ComponentActivity() {
    protected open val previewData: FitnessData? get() = null
    protected open val previewFontScale: Float? get() = null
    protected open val previewDark: Boolean? get() = null
    protected open fun previewAdvice(snapshot: AdviceSnapshot): SavedAdvice? = null
    protected open fun modelDiscovery() = ModelDiscovery()
    private val app get() = application as HealthApplication
    private var photo by mutableStateOf<Uri?>(null)
    private var cameraUri: Uri? = null
    private var recognizing by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var review by mutableStateOf<ReviewDraft?>(null)
    private var api by mutableStateOf(ApiSettings())
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> photo = uri }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success -> if (success) photo = cameraUri }
    private var pendingCsv by mutableStateOf<CsvRecords?>(null)
    private val csvPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if(count < 0) break; require(output.size() + count <= 1024 * 1024) { "CSV 文件最多 1 MB" }; output.write(buffer, 0, count) }
                        output.toByteArray()
                    } ?: error("无法打开文件")
                    require(bytes.size <= 1024 * 1024) { "CSV 文件最多 1 MB" }
                    CsvImport.parse(bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess { pendingCsv = it }.onFailure { toast(it.message ?: "CSV 读取失败") }
        }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val data = previewData ?: app.repository.data.first()
                val output = backup(data)
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(output.toByteArray(Charsets.UTF_8)) } ?: error("无法打开导出文件") }
            }.onSuccess { toast("记录已导出，不含 API 密钥") }.onFailure { toast("导出失败") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        photo = savedInstanceState?.getString("photo")?.let(Uri::parse)
        cameraUri = savedInstanceState?.getString("cameraUri")?.let(Uri::parse)
        api = if(previewData == null) app.apiSettings.load() else ApiSettings()
        setContent {
            val scale = previewFontScale
            if(scale == null) FitnessScreen()
            else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) { FitnessScreen() }
        }
    }
    override fun onStart() {
        super.onStart()
        if(previewData == null && app.link.status.value.enabled && !app.link.status.value.active && app.link.hasPermissions()) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(this,
                Intent(this, com.lrh.healthvivo.link.LinkService::class.java)) }
                .onFailure { toast("持续连接暂未恢复，请在“我的”重新开启") }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("photo", photo?.toString()); outState.putString("cameraUri", cameraUri?.toString())
        super.onSaveInstanceState(outState)
    }
    @Composable private fun FitnessScreen() {
        VitalityTheme(previewDark) {
            val colors = MaterialTheme.colorScheme
            val discovery = remember { modelDiscovery() }
            val adviceCoordinator = remember { if(previewData == null) app.advice else null }
            val dataFlow = remember { previewData?.let { kotlinx.coroutines.flow.flowOf(it) } ?: app.repository.data }
            val data by dataFlow.collectAsStateWithLifecycle(FitnessData())
            var tab by remember { mutableIntStateOf(if(previewData != null) intent.getIntExtra("preview_tab", 0).coerceIn(0, 4) else 0) }
            var today by remember { mutableStateOf(LocalDate.now()) }
            var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
            var meal by remember { mutableStateOf("breakfast") }
            var showMeal by remember { mutableStateOf(false) }
            var description by remember { mutableStateOf("") }
            var showProfile by remember { mutableStateOf(false) }
            var showWeight by remember { mutableStateOf(false) }
            var showBodyFat by remember { mutableStateOf(false) }
            var showTarget by remember { mutableStateOf(false) }
            var showSteps by remember { mutableStateOf(false) }
            var showFood by remember { mutableStateOf(false) }
            var selectedFood by remember { mutableStateOf<Food?>(null) }
            var editMeal by remember { mutableStateOf<MealEntry?>(null) }
            var showExercise by remember { mutableStateOf(false) }
            var editExercise by remember { mutableStateOf<ExerciseEntry?>(null) }
            var exerciseType by remember { mutableStateOf<ExerciseType?>(null) }
            var showApi by remember { mutableStateOf(false) }
            var deletion by remember { mutableStateOf<Pair<String, String>?>(null) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30000); today = LocalDate.now() } }
            val day = selectedDay ?: today
            val dayString = day.toString()
            val dayMeals = data.meals.filter { it.day == dayString }
            val dayExercises = data.exercises.filter { it.day == dayString }
            val weight = CalorieCalculator.weightAt(data, dayString)
            val currentWeight = CalorieCalculator.weightAt(data, today.toString())
            val dayBodyFat = BodyMetrics.bodyFatAt(data, dayString)
            val currentBodyFat = BodyMetrics.bodyFatAt(data, today.toString())
            val profile = data.profile?.let { it.copy(weightKg = currentWeight ?: it.weightKg) }
            val daySteps = data.steps.firstOrNull { it.day == dayString }
            val activity = CalorieCalculator.activities(daySteps, dayExercises, weight)
            val plan = data.profile?.takeIf { weight != null }?.copy(weightKg = weight!!)?.let { CalorieCalculator.plan(it, activity.kcal ?: 0.0) }
            val intake = dayMeals.sumOf { it.kcal }
            val adviceSnapshot = remember(data, dayString, today) { AdviceSummary.create(data, dayString) }
            Scaffold(containerColor = colors.background, bottomBar = {
                NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                    listOf("今日", "饮食", "运动", "趋势", "我的").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { AppIcon(listOf("home", "food", "dumbbell", "chart", "user")[index], modifier = Modifier.size(24.dp)) }, label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = colors.primary, selectedTextColor = colors.primary, indicatorColor = colors.primaryContainer, unselectedIconColor = colors.onSurfaceVariant))
                    }
                }
            }, topBar = {
                if(tab == 0) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.padding(end = 12.dp)) {
                        Text("今日", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        if(previewData != null) Text("演示数据", fontSize = 10.sp, color = colors.onSurfaceVariant)
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedDay = day.minusDays(1) }, modifier = Modifier.size(40.dp)) { AppIcon("left", "前一天", modifier = Modifier.size(18.dp)) }
                        TextButton(onClick = { selectedDay = null }, modifier = Modifier.weight(1f)) {
                            Text(if(day == today) "今天 · ${day.monthValue}/${day.dayOfMonth}" else if(day.year == today.year) "${day.monthValue}月${day.dayOfMonth}日" else "${day.year}/${day.monthValue}/${day.dayOfMonth}", fontSize = 13.sp, color = colors.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { selectedDay = day.plusDays(1) }, enabled = day < today, modifier = Modifier.size(40.dp)) { AppIcon("right", "后一天", modifier = Modifier.size(18.dp)) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(listOf("今日", "饮食", "运动", "趋势", "我的")[tab], fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                // The day's totals sit next to the page title, as the page's own summary card.
                                // The exercise total is the whole recorded activity, steps included, so it adds up
                                // with the step card and the workout cards shown below it.
                                if(tab == 1) TotalCard("今日摄入合计", number(intake), "kcal")
                                else if(tab == 2) TotalCard("今日运动消耗", number(activity.kcal), "kcal")
                            }
                            Text(if(previewData != null) "演示数据 · 不写入正式记录" else when(tab) {
                                2 -> "记录每一次努力 · 上方总计含运动与步数消耗"
                                else -> listOf("让记录成为日常", "每一餐，都有迹可循", "记录每一次努力", "关注变化，保持节奏", "活力记录")[tab]
                            }, fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                        if(tab == 2) AddButton("添加运动") { exerciseType = null; editExercise = null; showExercise = true }
                        else if(tab == 4) IconButton(onClick = { showApi = true }) { AppIcon("settings", "AI 配置") }
                    }
                }
            }) { padding ->
                val scroll = remember(tab) { ScrollState(0) }
                Column(Modifier.fillMaxSize().padding(padding).then(if(tab == 0) Modifier else Modifier.verticalScroll(scroll)).padding(horizontal = if(tab == 0) 12.dp else 20.dp, vertical = if(tab == 0) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if(tab == 0) 0.dp else 18.dp)) {
                    // Date switching lives on the 今日 page only; the other pages follow the day it selects
                    // and just say which day that is, so a past day is never mistaken for today.
                    if (tab in 1..3) Text(if(day == today) "今天 · ${day.monthValue}月${day.dayOfMonth}日" else "${day.year}年${day.monthValue}月${day.dayOfMonth}日",
                        fontSize = 12.sp, color = colors.onSurfaceVariant)
                    when (tab) {
                        0 -> {
                            HomeModules(listOf(
                                HomeModule("metrics", "每日步数") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Metric("每日步数 · 点击设置", daySteps?.steps?.toString() ?: "—", "步", Modifier.fillMaxWidth().clickable { showSteps = true }, "steps")
                                OutlinedButton(onClick = { showSteps = true }, modifier = Modifier.fillMaxWidth()) { Text(if(daySteps == null) "录入每日步数" else "修改每日步数") }
                            }
                                },
                                HomeModule("weight_bmi", "体重与 BMI") {
                            Panel("体重与 BMI") { WeightBmi(weight, data.profile) { showWeight = true } }
                                },
                                HomeModule("body_fat", "体脂率") {
                            Panel("体脂率") { BodyFatContent(dayBodyFat, data.profile) { showBodyFat = true } }
                                },
                                HomeModule("target", "目标体重") {
                            Panel("目标体重") {
                                if(profile?.targetWeightKg == null) Text("设置目标，让记录更有方向。", color = muted, fontSize = 13.sp)
                                else {
                                    ValueRow("我的目标", decimal(profile.targetWeightKg), "kg")
                                    currentWeight?.let { current ->
                                        val difference = current - profile.targetWeightKg!!
                                        Text(if(kotlin.math.abs(difference) < .05) "当前体重已到达目标附近，继续观察趋势。" else if(difference > 0) "距目标还需减重 ${decimal(difference)} kg" else "距目标还需增重 ${decimal(-difference)} kg", fontSize = 13.sp, color = muted)
                                    }
                                }
                                TextButton(onClick = { if(data.profile == null) showProfile = true else showTarget = true }) { Text(if(profile?.targetWeightKg == null) "设置目标体重" else "修改目标体重") }
                            }
                                },
                                HomeModule("advice", "AI 每日建议") {
                            key(dayString) { DailyAdviceCard(adviceSnapshot, api, adviceCoordinator, if(previewData == null) null else previewAdvice(adviceSnapshot)) { showApi = true } }
                                },
                                HomeModule("energy", "每日能量参考") {
                            if (profile == null) Panel("从个人资料开始") {
                                Text("填写年龄、身高、体重等信息，计算个人热量参考值。")
                                Button(onClick = { showProfile = true }) { Text("填写个人资料") }
                            } else {
                                Panel("每日能量参考") {
                                // The sources are listed with their own numbers instead of hidden behind a
                                // toggle: "reference kcal" alone does not say where the energy came from.
                                EnergyTotal("每日消耗参考", plan?.maintenanceKcal)
                                EnergyItem("静息代谢", plan?.restingKcal, "按年龄、身高、当日体重与性别参数估算")
                                EnergyItem("运动", activity.workoutKcal, if(dayExercises.isEmpty()) "当天没有运动记录" else "${dayExercises.size} 条运动记录的活动净消耗")
                                EnergyItem("步数", activity.stepsKcal, when {
                                    daySteps == null -> "未录入当天步数"
                                    daySteps.stepLengthCm == null -> "需要填写步长才能计算步数消耗"
                                    activity.workoutSteps > 0 -> "已扣除运动步数后的 ${activity.recordedSteps ?: 0} 步"
                                    else -> "全天 ${daySteps.steps} 步" })
                                EnergyTotal("当天登记摄入", intake)
                                listOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐", "snack" to "加餐").forEach { (kind, label) ->
                                    val rows = dayMeals.filter { it.meal == kind }
                                    EnergyItem(label, rows.sumOf { it.kcal }, if(rows.isEmpty()) "尚未记录" else "${rows.size} 项食物")
                                }
                                val balance = plan?.let { it.maintenanceKcal - intake }
                                val positive = balance != null && balance >= 0
                                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if(balance == null) colors.surfaceVariant else if(positive) Color(0xFFDAF4E5) else Color(0xFFFFDFE0)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("热量剩余参考", color = if(balance == null) muted else if(positive) Color(0xFF176640) else Color(0xFFAC2630), fontSize = 13.sp)
                                        Text(balance?.let { (if(it >= 0) "+" else "−") + number(kotlin.math.abs(it)) + " kcal" } ?: "待补充记录", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if(balance == null) muted else if(positive) Color(0xFF176640) else Color(0xFFAC2630))
                                    }
                                }
                                if (plan == null) Text("当前资料或当日体重不足以估算静息消耗，请继续记录。", fontSize = 13.sp)
                                Text("代谢＋运动＋步数＝每日消耗参考，不会重复计算同一次步行；步数按已填写的运动步数去重。剩余＝消耗参考−登记摄入。", color = muted, fontSize = 12.sp)
                                if (activity.overlapHandled) Text(stepSplitCaption(activity), color = muted, fontSize = 12.sp)
                                if (activity.stepsExceeded) Text("填写的运动步数超过当天总步数，已按总步数计算；请检查步数与总步数是否填反。", fontSize = 12.sp)
                                if (activity.pendingCount > 0) Text("${activity.pendingCount} 条运动待估算或热量口径未知，未计入活动合计。", fontSize = 12.sp)
                                }
                            }
                                },
                                HomeModule("quick", "快速记录") {
                            Panel("快速记录") {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    QuickTile("三餐", "food", Modifier.weight(1f)) { tab = 1 }
                                    QuickTile("体重", "scale", Modifier.weight(1f)) { showWeight = true }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    QuickTile("运动", "dumbbell", Modifier.weight(1f)) { exerciseType = null; showExercise = true; editExercise = null }
                                    QuickTile("步数", "steps", Modifier.weight(1f)) { showSteps = true }
                                }
                                QuickTile("体脂率", "scale", Modifier.fillMaxWidth()) { showBodyFat = true }
                            }
                                }
                            ), modifier = Modifier.weight(1f), persistOrder = previewData == null)
                        }
                        1 -> {
                            listOf("breakfast", "lunch", "dinner").forEach { kind ->
                                MealCard(kind, dayMeals.filter { it.meal == kind }) { meal = kind; showMeal = true }
                            }
                            val snack = dayMeals.filter { it.meal == "snack" }
                            Panel("加餐") {
                                Text(if(snack.isEmpty()) "尚未记录加餐" else "${snack.size} 项 · ${number(snack.sumOf { it.kcal })} 千卡", fontSize = 13.sp)
                                Text("加餐计入今日摄入合计，但不单独占用首页卡片。", color = muted, fontSize = 12.sp)
                            }
                        }
                        2 -> {
                            // The day's steps get their own card and are deliberately not counted as a workout record.
                            StepsCard(activity, daySteps) { showSteps = true }
                            if(dayExercises.isEmpty()) EmptyRecords("dumbbell", "今天还没有运动记录", "点击右上角＋，添加你的运动")
                            else {
                                Text("${dayExercises.size} 条运动记录", fontSize = 12.sp, color = colors.onSurfaceVariant)
                                dayExercises.forEach { entry -> WorkoutCard(entry,
                                    edit = { exerciseType = null; editExercise = entry; showExercise = true },
                                    delete = { deletion = "exercise" to entry.id }) }
                            }
                        }
                        3 -> {
                            Panel("体重趋势") {
                                WeightBmi(weight, data.profile) { showWeight = true }
                                val weights = data.weights.filter { it.day <= dayString }.take(14).reversed()
                                if (weights.isEmpty()) Text("记录体重后，会在这里显示变化。", color = muted)
                                else {
                                    WeightChart(weights)
                                    Text("最近 ${weights.size} 次记录 · 变化 ${decimal(weights.last().kg - weights.first().kg)} kg", fontSize = 13.sp)
                                    weights.takeLast(7).reversed().forEach { entry -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${entry.day.substring(5)}  ${decimal(entry.kg)} kg")
                                        TextButton(onClick = { deletion = "weight" to entry.day }) { Text("删除") }
                                    } }
                                }
                            }
                            Panel("体脂率趋势") {
                                val entries = data.bodyFats.filter { it.day <= dayString }.sortedByDescending { it.day }.take(14).reversed()
                                BodyFatTrend(entries, data.profile, { showBodyFat = true }, { deletion = "body_fat" to it })
                                BodyMetricReferences()
                            }
                            Panel("饮食 · 运动 · 热量剩余趋势") {
                                TrendCard(data, dayString)
                            }
                        }
                        else -> {
                            Panel("我的资料") {
                                if (profile == null) Text("还未填写个人资料。") else {
                                    Text("${profile.age} 岁 · ${decimal(profile.heightCm)} cm · ${decimal(currentWeight)} kg")
                                    Text("目标：${when(profile.goal) { "lose" -> "减脂"; "gain" -> "增重"; else -> "保持" }}")
                                    Text("目标体重：${decimal(profile.targetWeightKg)} kg")
                                    Text("BMI：${decimal(BodyMetrics.bmi(currentWeight, profile.heightCm))}")
                                    MetricStatus(BodyMetrics.bmiStatus(BodyMetrics.bmi(currentWeight, profile.heightCm), profile))
                                }
                                Text("体脂率：${currentBodyFat?.let { decimal(it.percent) + "% · " + it.day } ?: "未记录"}")
                                MetricStatus(BodyMetrics.fatStatus(currentBodyFat?.percent, profile))
                                Button(onClick = { showProfile = true }) { Text("编辑个人资料") }
                            }
                            Panel("AI 服务") {
                                Text(if(api.endpoint.isBlank()) "未配置，手动记录与食物库仍可使用。" else "服务：${Uri.parse(api.endpoint).host}\n模型：${api.model}")
                                OutlinedButton(onClick = { showApi = true }) { Text("配置 / 更换 API") }
                            }
                            if(previewData == null) {
                                com.lrh.healthvivo.link.LinkPanel(app.link)
                            }
                            Panel("我的食物库") {
                                Text("内置 ${app.catalog.foods.size} 种有来源的基础食物；自建 ${data.customFoods.size} 种。", fontSize = 13.sp)
                                Text("基础数据来自 USDA，具体品种、烹调与本地菜品会有差异；包装食物优先按营养标签填写。", fontSize = 12.sp, color = muted)
                                data.customFoods.forEach { entry -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${entry.name} ${number(entry.kcalPer100g)}/100g", modifier = Modifier.weight(1f), fontSize = 13.sp)
                                    TextButton(onClick = { deletion = "food" to entry.id }) { Text("删除") }
                                } }
                            }
                            Panel("数据保存在本机") {
                                Text("资料、饮食、步数、体重、体脂率与运动记录保存在手机。保存健康记录后会自动调用 AI 建议，发送身体资料、目标、当天餐食与活动、近7日体重和体脂率摘要；饮食 / 运动识别仍仅发送本次输入。", fontSize = 13.sp)
                                OutlinedButton(onClick = { exporter.launch("vitality-records-${today}.json") }) { Text("导出记录备份") }
                                OutlinedButton(onClick = { csvPicker.launch("*/*") }) { Text("导入步数 / 体重 / 运动 CSV") }
                                Text("步数表头 date,steps（可加 step_length_cm）；体重表头 date,weight_kg；运动表头 date,name,minutes,kcal。日期 YYYY-MM-DD，热量为千卡。", fontSize = 12.sp, color = muted)
                                TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pubmed.ncbi.nlm.nih.gov/2305711/"))) }) { Text("查看热量公式来源") }
                            }
                        }
                    }
                    if(tab == 4) Text("活力记录 ${BuildConfig.VERSION_NAME} · 数据保存在本机", color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            if(showMeal) MealWorkspace(dayString, meal, dayMeals.filter { it.meal == meal }, app.catalog, data.customFoods,
                api, recognizing, description, { description = it }, photo,
                photoPreview = { PhotoPreview(it) }, takePhoto = { takePhoto() }, pickPhoto = { imagePicker.launch("image/*") }, clearPhoto = { photo = null },
                localMatch = {
                    runCatching { app.catalog.parseLocalText(description) }.onSuccess { matches ->
                        review = ReviewDraft(dayString, meal, matches.map { (food, grams) -> MealCandidate(name = food.name, grams = grams, kcalPer100g = food.kcalPer100g, note = food.reference) }, "USDA 本地食物库（各项来源见记录名）")
                    }.onFailure { toast(it.message ?: "本地匹配失败") }
                }, recognize = { recognize(description, photo, dayString, meal) }, configure = { showApi = true },
                chooseFood = { selectedFood = it; editMeal = null; showFood = true }, edit = { editMeal = it; selectedFood = null; showFood = true },
                delete = { deletion = "meal" to it.id }, switchMeal = { meal = it }, dismiss = { showMeal = false })
            if (showProfile) ProfileDialog(profile, currentWeight, currentBodyFat, { showProfile = false }, saving) { value, fat -> saveAction({ app.repository.saveProfile(value, fat) }, adviceDays = setOf(today.toString(), dayString)) { showProfile = false } }
            if(showTarget) TargetWeightDialog(data.profile?.targetWeightKg, currentWeight, { showTarget = false }, saving) { value -> saveAction({ app.repository.saveTargetWeight(value) }, adviceDays = setOf(today.toString())) { showTarget = false } }
            if (showWeight) WeightDialog(dayString, data.weights.firstOrNull { it.day == dayString }, { showWeight = false }, saving) { value -> saveAction({ app.repository.saveWeight(value) }, adviceDays = setOf(value.day, today.toString())) { showWeight = false } }
            if (showBodyFat) BodyFatDialog(dayString, data.bodyFats.firstOrNull { it.day == dayString }, { showBodyFat = false }, saving) { value -> saveAction({ app.repository.saveBodyFat(value) }, adviceDays = setOf(value.day, today.toString())) { showBodyFat = false } }
            if (showSteps) StepsDialog(dayString, daySteps, { showSteps = false }, saving, delete = { showSteps = false; deletion = "steps" to dayString }) { value -> saveAction({ app.repository.saveSteps(value) }, adviceDays = setOf(value.day)) { showSteps = false } }
            if (showFood) FoodEntryDialog(editMeal?.day ?: dayString, meal, selectedFood, editMeal, { showFood = false }, saving) { value, keep ->
                saveAction({ app.repository.saveMealAndFood(value, if(keep) CustomFood(UUID.randomUUID().toString(), value.name, value.kcalPer100g) else null) }, adviceDays = setOf(value.day)) { showFood = false }
            }
            if (showExercise) ExerciseDialog(editExercise?.day ?: dayString, weight, editExercise, api, { showApi = true }, { showExercise = false }, saving, exerciseType) { value -> saveAction({ app.repository.saveExercise(value) }, adviceDays = setOf(value.day)) { showExercise = false } }
            if (showApi) ApiDialog(api, { showApi = false }, saving, save = { value -> saveAction({ withContext(Dispatchers.IO) { app.apiSettings.save(value) } }) { api = value; app.advice.configurationChanged(); showApi = false } }, clear = { saveAction({ withContext(Dispatchers.IO) { app.apiSettings.clear() } }) { api = ApiSettings(); app.advice.configurationChanged(); showApi = false } }, discovery = discovery)
            review?.let { draft -> ReviewDialog(draft, { review = null }, saving) { values -> saveAction({ app.repository.saveMeals(values) }, adviceDays = values.map { it.day }.toSet()) { review = null; description = ""; photo = null; toast("食物记录已保存") } } }
            pendingCsv?.let { records -> AlertDialog(onDismissRequest = { if(!saving) pendingCsv = null }, title = { Text("确认导入 CSV") }, text = { Text("将导入 ${records.weights.size} 条体重、${records.steps.size} 条步数、${records.exercises.size} 条运动。已有同日体重/步数会更新；相同文件的运动重复导入不会累加。") },
                confirmButton = { TextButton(onClick = { saveAction({ app.repository.importCsv(records) }, adviceDays = setOf(dayString, today.toString())) { pendingCsv = null; toast("CSV 已导入") } }, enabled = !saving) { Text("确认导入") } },
                dismissButton = { TextButton(onClick = { pendingCsv = null }, enabled = !saving) { Text("取消") } }) }
            deletion?.let { (type, id) ->
                val adviceDays = when(type) {
                    "meal" -> setOf(data.meals.firstOrNull { it.id == id }?.day ?: dayString)
                    "exercise" -> setOf(data.exercises.firstOrNull { it.id == id }?.day ?: dayString)
                    "steps" -> setOf(id)
                    "weight" -> setOf(id, today.toString())
                    "body_fat" -> setOf(id, today.toString())
                    else -> emptySet()
                }
                AlertDialog(onDismissRequest = { if(!saving) deletion = null }, title = { Text("删除这条记录？") }, text = { Text("删除后无法撤销，导出备份中的内容不受影响。") }, confirmButton = { TextButton(onClick = { saveAction({ when(type) { "meal" -> app.repository.dao.deleteMeal(id); "exercise" -> app.repository.dao.deleteExercise(id); "steps" -> app.repository.dao.deleteSteps(id); "weight" -> app.repository.dao.deleteWeight(id); "body_fat" -> app.repository.dao.deleteBodyFat(id); "food" -> app.repository.dao.deleteFood(id) } }, adviceDays = adviceDays) { deletion = null } }, enabled = !saving) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = null }, enabled = !saving) { Text("取消") } }) }
        }
    }
    private fun takePhoto() {
        runCatching {
            val folder = File(cacheDir, "meal_photos").apply { mkdirs() }
            val file = File(folder, "${UUID.randomUUID()}.jpg")
            cameraUri = FileProvider.getUriForFile(this, "$packageName.images", file)
            camera.launch(cameraUri!!)
        }.onFailure { toast("无法打开相机，可以改为选择图片") }
    }
    private fun recognize(text: String, uri: Uri?, day: String, meal: String) {
        if (recognizing) return
        recognizing = true
        val settings = api
        lifecycleScope.launch {
            try {
                val image = uri?.let { MealImage.dataUrl(this@MainActivity, it) }
                val items = app.recognition.recognize(settings, text, image)
                review = ReviewDraft(day, meal, items, "AI估计 / ${Uri.parse(settings.endpoint).host} / ${settings.model}")
            } catch (error: Exception) {
                toast(when(error) { is IllegalArgumentException -> error.message ?: "请检查输入"; is java.net.SocketTimeoutException -> "识别请求超时，请稍后重试"; is java.io.IOException -> "无法连接识别服务，请检查网络与 API 地址"; else -> "识别结果格式不兼容，请更换模型或手动记录" })
            } finally { recognizing = false }
        }
    }
    private fun saveAction(action: suspend () -> Unit, adviceDays: Set<String> = emptySet(), success: () -> Unit) {
        if(previewData != null) { toast("演示界面，不保存到正式记录"); return }
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try { action(); success(); if(adviceDays.isNotEmpty()) app.advice.request(adviceDays) } catch (error: Exception) { toast(if(error is IllegalArgumentException) error.message ?: "请检查输入" else "保存失败，原记录已保留") }
            finally { saving = false }
        }
    }
    @Composable private fun WeightChart(weights: List<WeightEntry>) {
        val line = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.fillMaxWidth().height(125.dp)) {
            val min = weights.minOf { it.kg } - 0.5; val max = weights.maxOf { it.kg } + 0.5
            val first = LocalDate.parse(weights.first().day).toEpochDay()
            val span = LocalDate.parse(weights.last().day).toEpochDay() - first
            (1..3).forEach { index -> drawLine(grid, Offset(0f, size.height * index / 4), Offset(size.width, size.height * index / 4), strokeWidth = 1f) }
            val points = weights.map { w -> Offset(if(span == 0L) size.width / 2 else (8 + (LocalDate.parse(w.day).toEpochDay() - first).toFloat() / span * (size.width - 16)), (size.height - 8 - ((w.kg - min) / (max - min)) * (size.height - 16)).toFloat()) }
            if(points.size > 1) {
                val fill = androidx.compose.ui.graphics.Path().apply { moveTo(points.first().x, size.height); points.forEach { lineTo(it.x, it.y) }; lineTo(points.last().x, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = .22f), line.copy(alpha = .01f))))
            }
            points.zipWithNext().forEach { (start, end) -> drawLine(line, start, end, strokeWidth = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
            points.forEach { drawCircle(line, 7f, it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(weights.first().day.substring(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(weights.last().day.substring(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable private fun PhotoPreview(uri: Uri) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
            value = runCatching { MealImage.preview(this@MainActivity, uri) }.getOrNull()
        }
        bitmap?.let { image -> Image(image.asImageBitmap(), contentDescription = "本次待识别的食物照片", modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit) }
    }
    @Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
        val colors = MaterialTheme.colorScheme
        val icon = when { title.contains("步数") -> "steps"; title.contains("体重") || title.contains("体脂") -> "scale"; title.contains("AI") || title.contains("识别") -> "sparkles"; title.contains("资料") -> "user"; title.contains("运动") -> "dumbbell"; title.contains("数据") -> "shield"; title.contains("能量") -> "flame"; title.contains("建议") -> "leaf"; title.contains("快速") -> "plus"; else -> "food" }
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(if(LocalHomeReordering.current) 20.dp else 22.dp), colors = CardDefaults.cardColors(containerColor = colors.surface)) {
            Column(Modifier.padding(if(LocalHomeReordering.current) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if(LocalHomeReordering.current) 12.dp else 14.dp)) {
                Row(Modifier.padding(end = if(LocalHomeReordering.current) 36.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppIcon(icon, modifier = Modifier.size(18.dp), tint = colors.primary); Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                content()
            }
        }
    }
    @Composable private fun Metric(label: String, value: String, unit: String, modifier: Modifier, icon: String) {
        Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(end = if(LocalHomeReordering.current) 24.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppIcon(icon, tint = MaterialTheme.colorScheme.primary); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold); Text(unit, modifier = Modifier.padding(bottom = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    @Composable private fun QuickTile(label: String, icon: String, modifier: Modifier, click: () -> Unit) {
        Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { AppIcon(icon, tint = MaterialTheme.colorScheme.primary); Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
    @Composable private fun IntakeHero(intake: Double?, meals: List<MealEntry>) {
        val coverage = meals.map { it.meal }.toSet().intersect(setOf("breakfast", "lunch", "dinner")).size
        val largeText = LocalDensity.current.fontScale > 1.35f
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF514BE0), Color(0xFF7773EF)))).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppIcon("flame", tint = Color(0xFFFFC8A1)); Text("已记录摄入", color = Color.White.copy(alpha = .8f), fontSize = 13.sp) }
                    Text(number(intake), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("千卡 · ${meals.size} 条食物记录", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                    if(largeText) Text("三餐已记录 $coverage / 3", color = Color.White, fontSize = 11.sp)
                }
                if(!largeText) Box(Modifier.padding(top = if(LocalHomeReordering.current) 24.dp else 0.dp).size(92.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { coverage / 3f }, modifier = Modifier.fillMaxSize(), color = Color(0xFFE0DEFF), trackColor = Color.White.copy(alpha = .16f), strokeWidth = 7.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$coverage/3", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold); Text("餐次已记", color = Color.White.copy(alpha = .7f), fontSize = 10.sp) }
                }
            }
        }
    }
    @Composable private fun ValueRow(label: String, value: String, unit: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp); Text("$value $unit", fontSize = 14.sp) }
    }
    /** The page's own total, sitting next to its title. */
    @Composable private fun TotalCard(caption: String, value: String, unit: String) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(caption, fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(3.dp))
                    Text(unit, fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
    /** The headline of an energy group: the number the items below it add up to. */
    @Composable private fun EnergyTotal(label: String, value: Double?) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${number(value)} 千卡", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
    /** One source of that total, indented, with its own number spelled out. */
    @Composable private fun EnergyItem(label: String, value: Double?, note: String) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, fontSize = 13.sp)
                Text(note, fontSize = 11.sp, color = muted)
            }
            Text("${number(value)} 千卡", fontSize = 14.sp)
        }
    }
    private fun backup(data: FitnessData): String {
        fun meal(value: MealEntry) = JSONObject().put("id", value.id).put("day", value.day).put("meal", value.meal).put("name", value.name).put("grams", value.grams).put("kcalPer100g", value.kcalPer100g)
            .put("proteinPer100g", value.proteinPer100g ?: JSONObject.NULL).put("fatPer100g", value.fatPer100g ?: JSONObject.NULL).put("carbsPer100g", value.carbsPer100g ?: JSONObject.NULL)
            .put("source", value.source).put("sourceReference", value.sourceReference).put("recordedAt", value.recordedAt)
        return JSONObject().put("format", "vitality.local.v4").put("exportedAt", System.currentTimeMillis())
            .put("profile", data.profile?.let { JSONObject().put("age", it.age).put("heightCm", it.heightCm).put("weightKg", it.weightKg).put("sex", it.sex).put("goal", it.goal).put("targetWeightKg", it.targetWeightKg ?: JSONObject.NULL).put("specialCondition", it.specialCondition).put("updatedAt", it.updatedAt) } ?: JSONObject.NULL)
            .put("weights", JSONArray(data.weights.map { JSONObject().put("day", it.day).put("kg", it.kg).put("recordedAt", it.recordedAt) }))
            .put("bodyFats", JSONArray(data.bodyFats.map { JSONObject().put("day", it.day).put("percent", it.percent).put("recordedAt", it.recordedAt) }))
            .put("meals", JSONArray(data.meals.map(::meal)))
            .put("exercises", JSONArray(data.exercises.map { JSONObject().put("id", it.id).put("day", it.day).put("name", it.name).put("minutes", it.minutes ?: JSONObject.NULL).put("kcal", it.kcal ?: JSONObject.NULL).put("method", it.method).put("weightKg", it.weightKg ?: JSONObject.NULL).put("met", it.met ?: JSONObject.NULL).put("category", it.category).put("inputMode", it.inputMode).put("distanceKm", it.distanceKm ?: JSONObject.NULL).put("sets", it.sets ?: JSONObject.NULL).put("reps", it.reps ?: JSONObject.NULL).put("loadKg", it.loadKg ?: JSONObject.NULL).put("includedInSteps", it.includedInSteps).put("steps", it.steps ?: JSONObject.NULL).put("netCalories", it.netCalories).put("estimateNote", it.estimateNote).put("details", it.details).put("recordedAt", it.recordedAt) }))
            .put("steps", JSONArray(data.steps.map { JSONObject().put("day", it.day).put("steps", it.steps).put("stepLengthCm", it.stepLengthCm ?: JSONObject.NULL).put("recordedAt", it.recordedAt) }))
            .put("customFoods", JSONArray(data.customFoods.map { JSONObject().put("id", it.id).put("name", it.name).put("kcalPer100g", it.kcalPer100g) })).toString(2)
    }
    private fun number(value: Double?) = value?.let { "%.0f".format(it) } ?: "—"
    private fun decimal(value: Double?) = value?.let { "%.1f".format(it) } ?: "—"
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}
