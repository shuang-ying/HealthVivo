package com.lrh.healthvivo.debug

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lrh.healthvivo.MainActivity
import com.lrh.healthvivo.ai.ModelDiscovery
import com.lrh.healthvivo.ai.ModelsTransport
import com.lrh.healthvivo.ai.AdviceSnapshot
import com.lrh.healthvivo.ai.DailyAdvice
import com.lrh.healthvivo.ai.SavedAdvice
import com.lrh.healthvivo.fitness.*
import java.io.File
import java.time.LocalDate

// Debug only: exercises the real screen with fixtures. MainActivity blocks saves and uses no API key.
class PreviewActivity : MainActivity() {
    override fun previewAdvice(snapshot: AdviceSnapshot) = SavedAdvice(snapshot.fingerprint, System.currentTimeMillis(), "离线演示建议",
        DailyAdvice("演示数据：已记录早餐与午餐，体重 70.3 kg、BMI 正常，目标为减脂。",
            "已记录摄入不是全天摄入，继续记录晚餐；结合连续体重与体脂率趋势调整。",
            "晚餐可选择熟杂粮饭约 100–150 g、清蒸鱼约 100–150 g 和蔬菜约 200 g；也可用豆腐替换鱼。份量为示例，按实际需要调整。",
            "已记录快走 30 分钟，今天可保持轻松活动；另一天安排基础力量训练，循序增加，疲劳时休息。",
            listOf("确认当天是否已吃晚餐")))
    override val previewFontScale get() = intent.getFloatExtra("preview_font_scale", 1f).coerceIn(1f, 2f)
    override val previewDark get() = intent.getBooleanExtra("preview_dark", false)
    override fun modelDiscovery() = ModelDiscovery(ModelsTransport { _, url ->
        File(cacheDir, "model-detection-test.txt").apply { writeText(((readTextOrZero()) + 1).toString()) }
        require(!url.contains("unsupported.example.com")) { "此服务未开放模型列表，可手动填写模型" }
        """{"data":[{"id":"demo-vision"},{"id":"demo-text"}]}"""
    })
    private fun File.readTextOrZero() = if(exists()) readText().toIntOrNull() ?: 0 else 0
    override val previewData: FitnessData by lazy {
        val today = LocalDate.now()
        val day = today.toString()
        FitnessData(
            profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.3, sex = "male", goal = "lose", updatedAt = 1),
            weights = (0..6).map { offset -> WeightEntry(today.minusDays(offset.toLong()).toString(), 70.3 + offset * .1, 1) },
            meals = listOf(
                MealEntry("demo1", day, "breakfast", "全麦面包", 100.0, 247.0, source = "database", recordedAt = 1),
                MealEntry("demo2", day, "breakfast", "全脂牛奶", 200.0, 61.0, source = "database", recordedAt = 1),
                MealEntry("demo3", day, "lunch", "熟米饭", 150.0, 130.0, source = "database", recordedAt = 2),
                MealEntry("demo4", day, "lunch", "烤鸡胸肉（去皮）", 150.0, 165.0, source = "database", recordedAt = 2),
                MealEntry("demo5", day, "lunch", "水煮菠菜（无盐）", 100.0, 23.0, source = "database", recordedAt = 2)
            ), steps = listOf(DailySteps(day, 6800, 70.0, 1)),
            bodyFats = (0..6).map { offset -> BodyFatEntry(today.minusDays(offset.toLong()).toString(), 19.5 + offset * .1, 1) },
            exercises = listOf(ExerciseEntry("demo-walk", day, "快走（约 5.6–6.3 km/h）", 30.0, CalorieCalculator.netExerciseKcal(4.8, 70.3, 30.0), "met", 70.3, 4.8, 1,
                category = "aerobic", includedInSteps = true, netCalories = true, estimateNote = "2024 Compendium 17200 · 4.8 MET，演示记录"))
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("preview_capture", false)) window.decorView.postDelayed({ capture() }, 2200)
    }
    private fun capture() {
        val decor = window.decorView
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val top = bars?.top ?: 0; val bottom = bars?.bottom ?: 0
        if(decor.width <= 0 || decor.height <= top + bottom) return
        val bitmap = Bitmap.createBitmap(decor.width, decor.height - top - bottom, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, Rect(0, top, decor.width, decor.height - bottom), bitmap, { result ->
            try {
                if(result == PixelCopy.SUCCESS) File(cacheDir, "ui-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }, Handler(Looper.getMainLooper()))
    }
}
