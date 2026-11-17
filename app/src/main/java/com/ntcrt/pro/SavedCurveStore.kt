package com.ntcrt.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 已保存或历史计算出的 NTC 曲线档案条目
 */
data class SavedCurveRecord(
    val id: String,
    val title: String,
    val savedAtMs: Long,
    val isPinned: Boolean,
    val spec: NtcSpec
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(savedAtMs))
    }

    fun summaryLine(): String {
        val bInt = spec.bValue.roundToInt()
        val rTol = NtcMathEngine.formatTolPair(spec.r25TolPlusPct, spec.r25TolMinusPct)
        val bTol = NtcMathEngine.formatTolPair(spec.bTolPlusPct, spec.bTolMinusPct)
        val tMinStr = NtcMathEngine.formatTemp(spec.tMin)
        val tMaxStr = NtcMathEngine.formatTemp(spec.tMax)
        return "R25=${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)} ($rTol) · B=${bInt}K ($bTol) · ${tMinStr}~${tMaxStr}℃"
    }
}

/**
 * 本地曲线计算档案与最近历史持久化管理器（纯 SharedPreferences + JSON）
 */
object SavedCurveStore {

    private const val PREF_NAME = "ntc_saved_curves_store"
    private const val KEY_RECORDS = "records_json"
    private const val MAX_RECENT_AUTO = 10

    fun loadAll(context: Context): List<SavedCurveRecord> {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_RECORDS, "[]") ?: "[]"
        val result = ArrayList<SavedCurveRecord>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val specObj = obj.getJSONObject("spec")
                val legacyRTol = specObj.optDouble("r25TolerancePct", 17.0)
                val legacyIncludeB = specObj.optBoolean("includeBTolerance", false)
                val legacyBTol = if (legacyIncludeB) specObj.optDouble("bTolerancePct", 0.0) else 0.0

                val spec = NtcSpec(
                    manufacturer = specObj.optString("manufacturer", ""),
                    partNumber = specObj.optString("partNumber", ""),
                    r25Ohm = specObj.optDouble("r25Ohm", 5.0),
                    bValue = specObj.optDouble("bValue", 4100.0),
                    r25TolPlusPct = specObj.optDouble("r25TolPlusPct", legacyRTol),
                    r25TolMinusPct = specObj.optDouble("r25TolMinusPct", legacyRTol),
                    bTolPlusPct = specObj.optDouble("bTolPlusPct", legacyBTol),
                    bTolMinusPct = specObj.optDouble("bTolMinusPct", legacyBTol),
                    tMin = specObj.optDouble("tMin", 20.0),
                    tMax = specObj.optDouble("tMax", 36.5),
                    tStep = specObj.optDouble("tStep", 0.5)
                )
                result.add(
                    SavedCurveRecord(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        title = obj.optString("title", ""),
                        savedAtMs = obj.optLong("savedAtMs", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned", true),
                        spec = spec
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return result.sortedWith(
            compareByDescending<SavedCurveRecord> { it.isPinned }
                .thenByDescending { it.savedAtMs }
        )
    }

    private fun saveAll(context: Context, list: List<SavedCurveRecord>) {
        val arr = JSONArray()
        for (rec in list) {
            val specObj = JSONObject().apply {
                put("manufacturer", rec.spec.manufacturer)
                put("partNumber", rec.spec.partNumber)
                put("r25Ohm", rec.spec.r25Ohm)
                put("bValue", rec.spec.bValue)
                put("r25TolPlusPct", rec.spec.r25TolPlusPct)
                put("r25TolMinusPct", rec.spec.r25TolMinusPct)
                put("bTolPlusPct", rec.spec.bTolPlusPct)
                put("bTolMinusPct", rec.spec.bTolMinusPct)
                put("tMin", rec.spec.tMin)
                put("tMax", rec.spec.tMax)
                put("tStep", rec.spec.tStep)
            }
            val obj = JSONObject().apply {
                put("id", rec.id)
                put("title", rec.title)
                put("savedAtMs", rec.savedAtMs)
                put("isPinned", rec.isPinned)
                put("spec", specObj)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, arr.toString())
            .apply()
    }

    fun defaultTitleFor(spec: NtcSpec): String {
        val prefix = listOf(spec.manufacturer.trim(), spec.partNumber.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val core = "R25=${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)} / B${spec.bValue.roundToInt()}"
        return if (prefix.isNotEmpty()) "$prefix ($core)" else core
    }

    fun savePinned(context: Context, spec: NtcSpec, customTitle: String): SavedCurveRecord {
        val existing = loadAll(context).toMutableList()
        val now = System.currentTimeMillis()
        val finalTitle = customTitle.trim().ifEmpty { defaultTitleFor(spec) }
        val rec = SavedCurveRecord(
            id = "pin_$now",
            title = finalTitle,
            savedAtMs = now,
            isPinned = true,
            spec = spec
        )
        existing.add(0, rec)
        saveAll(context, existing)
        return rec
    }

    fun recordRecentSnapshot(context: Context, spec: NtcSpec) {
        val all = loadAll(context)
        val pinned = all.filter { it.isPinned }
        val recent = all.filter { !it.isPinned }.toMutableList()

        val duplicateIdx = recent.indexOfFirst { isSameCoreParams(it.spec, spec) }
        if (duplicateIdx >= 0) {
            recent.removeAt(duplicateIdx)
        }
        if (pinned.any { isSameCoreParams(it.spec, spec) }) {
            return
        }

        val now = System.currentTimeMillis()
        recent.add(
            0,
            SavedCurveRecord(
                id = "auto_$now",
                title = defaultTitleFor(spec),
                savedAtMs = now,
                isPinned = false,
                spec = spec
            )
        )
        saveAll(context, pinned + recent.take(MAX_RECENT_AUTO))
    }

    fun deleteById(context: Context, id: String) {
        val filtered = loadAll(context).filterNot { it.id == id }
        saveAll(context, filtered)
    }

    fun isSameCoreParams(a: NtcSpec, b: NtcSpec): Boolean {
        return abs(a.r25Ohm - b.r25Ohm) < 1e-6 &&
            abs(a.bValue - b.bValue) < 1e-4 &&
            abs(a.r25TolPlusPct - b.r25TolPlusPct) < 1e-4 &&
            abs(a.r25TolMinusPct - b.r25TolMinusPct) < 1e-4 &&
            abs(a.bTolPlusPct - b.bTolPlusPct) < 1e-4 &&
            abs(a.bTolMinusPct - b.bTolMinusPct) < 1e-4 &&
            abs(a.tMin - b.tMin) < 1e-4 &&
            abs(a.tMax - b.tMax) < 1e-4 &&
            abs(a.tStep - b.tStep) < 1e-4
    }
}
