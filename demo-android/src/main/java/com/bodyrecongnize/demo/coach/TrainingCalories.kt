package com.bodyrecongnize.demo.coach

import java.util.Locale

/** Offline gross energy estimate for tracked active time, not a physiological measurement. */
object TrainingCalories {
    const val METHOD = "met-gross-active-v1"
    const val REFERENCE_URL = "https://pacompendium.com/conditioning-exercise/"
    const val WEIGHT_ERROR = "请输入20—300 kg的体重，最多一位小数；也可以留空"

    enum class Source { CAMERA, VIDEO }
    enum class Status { ESTIMATED, MISSING_WEIGHT, UNSUPPORTED, VIDEO, LEGACY }
    data class WeightInput(val valid: Boolean, val kilograms: Double?)
    data class Reference(val code: String, val label: String, val met: Double, val approximate: Boolean)

    /** Immutable per-session inputs; preferences are never read while calculating or reading history. */
    data class Basis(val source: Source, val weightKg: Double?, val reference: Reference?) {
        fun estimate(activeMs: Long): Estimate {
            require(activeMs >= 0)
            if (source == Source.VIDEO) return Estimate(Status.VIDEO, activeMs)
            if (!validWeight(weightKg)) return Estimate(Status.MISSING_WEIGHT, activeMs)
            if (reference == null) return Estimate(Status.UNSUPPORTED, activeMs, weightKg = weightKg)
            val kcal = reference.met * 3.5 * weightKg!! / 200.0 * (activeMs / 60_000.0)
            return Estimate(Status.ESTIMATED, activeMs, kcal, weightKg, reference)
        }
    }

    data class Estimate(val status: Status, val activeMs: Long = 0, val kcal: Double? = null,
                        val weightKg: Double? = null, val reference: Reference? = null) {
        val valueLabel: String get() = kcal?.let {
            if (it > 0 && it < .1) "<0.1" else "≈ ${String.format(Locale.CHINA, "%.1f", it)}"
        } ?: "—"
        val summaryLabel: String get() = when (status) {
            Status.ESTIMATED -> "热量消耗（估算）$valueLabel 千卡"
            Status.MISSING_WEIGHT -> "热量未估算 · 未填写体重"
            Status.UNSUPPORTED -> "热量未估算 · 暂无估算依据"
            Status.VIDEO -> "视频分析 · 不计入个人消耗"
            Status.LEGACY -> "热量未估算 · 旧记录未保存依据"
        }
        fun explanation(): String = if (status == Status.ESTIMATED && reference != null) {
            "本次体重 ${formatWeight(weightKg!!)} kg\n" +
                "参考类别 ${reference.label}${if (reference.approximate) "（类别近似）" else ""}\n" +
                "参考强度 ${reference.met} MET · 2024 Compendium ${reference.code}\n" +
                "参与估算的有效时长 ${activeMs / 1000} 秒\n" +
                "有效训练时段总消耗，含静息消耗；暂停、休息和识别中断不持续累计。\n" +
                "按普通强度估算，摄像头未测量实际代谢强度；组内静止、负重及个人差异会影响误差。"
        } else summaryLabel

        fun toFields(): Map<String, Any?> = mapOf(
            "version" to 1, "method" to METHOD, "status" to status.name,
            "activeMs" to activeMs, "kcal" to kcal, "weightKg" to weightKg,
            "referenceCode" to reference?.code, "referenceLabel" to reference?.label,
            "met" to reference?.met, "categoryApproximation" to reference?.approximate,
            "referenceUrl" to if (reference != null) REFERENCE_URL else null
        )
    }

    private val bodyWeight = Reference("02056", "普通徒手抗阻训练", 3.0, false)
    private val resistance = Reference("02054", "普通多动作抗阻训练", 3.5, true)
    private val calisthenics = Reference("02030", "轻中强度徒手操", 3.5, true)

    fun referenceFor(exercise: String): Reference? = when (exercise) {
        "SQUAT", "PUSH_UP" -> bodyWeight
        "BICEP_CURL", "LATERAL_RAISE" -> resistance
        "ARM_RAISE" -> calisthenics
        else -> null
    }

    fun forSession(exercise: String, source: Source, weightKg: Double?): Basis =
        if (source == Source.VIDEO) Basis(source, null, null)
        else Basis(source, weightKg?.takeIf { validWeight(it) }, referenceFor(exercise))

    fun parseWeight(text: String): WeightInput {
        val value = text.trim()
        if (value.isEmpty()) return WeightInput(true, null)
        if (!Regex("[0-9]+(?:[.,][0-9])?").matches(value)) return WeightInput(false, null)
        val kg = value.replace(',', '.').toDoubleOrNull()
        return WeightInput(validWeight(kg), kg?.takeIf { validWeight(it) })
    }

    private fun validWeight(kg: Double?) = kg != null && kg.isFinite() && kg in 20.0..300.0
    fun formatWeight(kg: Double): String = String.format(Locale.CHINA, "%.1f", kg).removeSuffix(".0")

    /** Reads recorded values rather than recalculating against today's preferences or MET table. */
    fun fromStored(source: Source, fields: Map<String, Any?>?): Estimate {
        if (source == Source.VIDEO) return Estimate(Status.VIDEO)
        if (fields == null || fields["version"] != 1 || fields["method"] != METHOD) return Estimate(Status.LEGACY)
        val status = Status.values().firstOrNull { it.name == fields["status"] } ?: return Estimate(Status.LEGACY)
        val activeMs = (fields["activeMs"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: return Estimate(Status.LEGACY)
        if (status == Status.MISSING_WEIGHT || status == Status.UNSUPPORTED) return Estimate(status, activeMs)
        if (status != Status.ESTIMATED) return Estimate(Status.LEGACY)
        val kcal = (fields["kcal"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: return Estimate(Status.LEGACY)
        val weight = (fields["weightKg"] as? Number)?.toDouble()?.takeIf { validWeight(it) } ?: return Estimate(Status.LEGACY)
        val met = (fields["met"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return Estimate(Status.LEGACY)
        val code = (fields["referenceCode"] as? String)?.takeIf { it.isNotBlank() } ?: return Estimate(Status.LEGACY)
        val label = (fields["referenceLabel"] as? String)?.takeIf { it.isNotBlank() } ?: return Estimate(Status.LEGACY)
        val approximate = fields["categoryApproximation"] as? Boolean ?: return Estimate(Status.LEGACY)
        return Estimate(status, activeMs, kcal, weight, Reference(code, label, met, approximate))
    }
}
