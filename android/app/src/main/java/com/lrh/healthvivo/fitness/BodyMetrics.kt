package com.lrh.healthvivo.fitness

import java.math.BigDecimal
import java.math.RoundingMode

data class BodyMetricStatus(val label: String, val normal: Boolean?, val explanation: String)
data class BodyReferenceScale(val min: Double, val max: Double, val breaks: List<Double>, val labels: List<String>, val colors: List<String>, val unit: String = "")
data class BodyFatReference(val low: Double, val high: Double)
data class BmiWeightAdjustment(val direction: String, val kilograms: Double, val boundaryBmi: Double)

/** Reference classifications only; body fat always comes from a user-entered measurement. */
object BodyMetrics {
    const val BMI_SOURCE = "https://www.nhc.gov.cn/jkj/s10045/200907/943149b60565497bb3e25783a0e3de68.shtml"
    const val FAT_SOURCE = "https://pubmed.ncbi.nlm.nih.gov/10966886/"
    private const val YELLOW = "#E9B748"
    private const val GREEN = "#42AC81"
    private const val RED = "#E16B73"
    fun bmiScale(profile: Profile?): BodyReferenceScale? = profile?.takeIf { it.age >= 18 && !it.specialCondition }?.let {
        BodyReferenceScale(12.0, 36.0, listOf(18.5, 24.0, 28.0), listOf("偏低", "正常", "超重", "肥胖"), listOf(YELLOW, GREEN, YELLOW, RED))
    }
    fun fatReference(profile: Profile?): BodyFatReference? {
        if(profile == null || profile.sex !in listOf("male", "female") || profile.age !in 20..79 || profile.specialCondition) return null
        val band = when(profile.age) { in 20..39 -> 0; in 40..59 -> 1; else -> 2 }
        return BodyFatReference((if(profile.sex == "male") listOf(8.0, 11.0, 13.0) else listOf(21.0, 23.0, 24.0))[band],
            (if(profile.sex == "male") listOf(20.0, 22.0, 25.0) else listOf(33.0, 34.0, 36.0))[band])
    }
    fun fatScale(profile: Profile?): BodyReferenceScale? = fatReference(profile)?.let {
        BodyReferenceScale(0.0, 50.0, listOf(it.low, it.high), listOf("偏低", "正常", "偏高"), listOf(YELLOW, GREEN, RED), "%")
    }
    fun bmiWeightAdjustment(weight: Double?, profile: Profile?): BmiWeightAdjustment? {
        if(bmiScale(profile) == null || profile == null || weight == null) return null
        val value = bmi(weight, profile.heightCm) ?: return null
        val boundary = when { value < 18.5 -> 18.5; value >= 24.0 -> 23.9; else -> return null }
        val height = BigDecimal.valueOf(profile.heightCm).movePointLeft(2)
        val target = BigDecimal.valueOf(boundary).multiply(height.pow(2))
        val delta = if(value < 18.5) target.subtract(BigDecimal.valueOf(weight)) else BigDecimal.valueOf(weight).subtract(target)
        return BmiWeightAdjustment(if(value < 18.5) "增重" else "减重", delta.max(BigDecimal.ZERO).setScale(1, RoundingMode.CEILING).toDouble(), boundary)
    }
    fun bodyFatAt(data: FitnessData, day: String): BodyFatEntry? = data.bodyFats.filter { it.day <= day }.maxByOrNull { it.day }
    fun bmi(weight: Double?, height: Double?): Double? {
        if(weight == null || height == null || !weight.isFinite() || !height.isFinite() || weight <= 0 || height <= 0) return null
        return BigDecimal.valueOf(weight / ((height / 100) * (height / 100))).setScale(1, RoundingMode.HALF_UP).toDouble()
    }
    fun bmiStatus(value: Double?, profile: Profile?): BodyMetricStatus {
        if(value == null || profile == null) return BodyMetricStatus("待完善资料", null, "填写身高和体重后显示 BMI")
        if(profile.age < 18 || profile.specialCondition) return BodyMetricStatus("暂不评级", null, "成人 BMI 分类不直接适用于当前资料")
        return when {
            value < 18.5 -> BodyMetricStatus("偏低", false, "低于成人参考范围")
            value < 24 -> BodyMetricStatus("正常", true, "成人参考范围 18.5–23.9")
            value < 28 -> BodyMetricStatus("偏高 · 超重", false, "达到成人超重参考界值")
            else -> BodyMetricStatus("偏高 · 肥胖范围", false, "达到成人肥胖参考界值")
        }
    }
    fun fatStatus(value: Double?, profile: Profile?): BodyMetricStatus {
        if(value == null) return BodyMetricStatus("未记录", null, "手动填写体脂秤或体测的结果")
        if(profile == null || profile.sex == "unspecified") return BodyMetricStatus("待完善资料", null, "填写年龄和性别参数后显示参考状态")
        if(profile.age !in 20..79 || profile.specialCondition) return BodyMetricStatus("暂不评级", null, "当前资料超出此参考表的适用范围")
        // Gallagher et al. (2000), Table 4. Provisional reference, not a clinical diagnosis.
        val range = fatReference(profile) ?: return BodyMetricStatus("暂不评级", null, "当前资料超出此参考表的适用范围")
        val low = range.low; val high = range.high
        val reference = "年龄 / 性别参考 ${low.toInt()}% ≤ 体脂率 < ${high.toInt()}%"
        return when {
            value < low -> BodyMetricStatus("参考偏低", false, reference)
            value < high -> BodyMetricStatus("参考正常", true, reference)
            else -> BodyMetricStatus("参考偏高", false, reference)
        }
    }
}
