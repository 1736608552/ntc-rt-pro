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
    val isPinned: Boolean, // true=用户手动保存的永久记录; false=最近自动快照
    val spec: NtcSpec
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(savedAtMs))
    }

    fun summaryLine(): String {
        val bInt = spec.bValue.roundToInt()
        val tMinStr = NtcMathEngine.formatTemp(spec.tMin)
        val tMaxStr = NtcMathEngine.formatTemp(spec.tMax)
        return "R25=${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)} · B=${bInt}K · ±${trimFloat(spec.r25TolerancePct)}% · ${tMinStr}~${tMaxStr}℃"
    }

    private fun trimFloat(v: Double): String {
        return if (abs(v - v.roundToInt()) < 1e-6) {
            v.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        }
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
                val spec = NtcSpec(
                    manufacturer = specObj.optString("manufacturer", ""),
                    partNumber = specObj.optString("partNumber", ""),
                    r25Ohm = specObj.optDouble("r25Ohm", 5.0),
                    bValue = specObj.optDouble("bValue", 4100.0),
                    r25TolerancePct = specObj.optDouble("r25TolerancePct", 17.0),
                    bTolerancePct = specObj.optDouble("bTolerancePct", 1.0),
                    includeBTolerance = specObj.optBoolean("includeBTolerance", false),
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
                put("r25TolerancePct", rec.spec.r25TolerancePct)
                put("bTolerancePct", rec.spec.bTolerancePct)
                put("includeBTolerance", rec.spec.includeBTolerance)
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

    /**
     * 手动保存当前计算曲线（永久保留）
     */
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

    /**
     * 记录一次最近自动计算快照（去重且限制最多保留 MAX_RECENT_AUTO 条）
     */
    fun recordRecentSnapshot(context: Context, spec: NtcSpec) {
        val all = loadAll(context)
        val pinned = all.filter { it.isPinned }
        val recent = all.filter { !it.isPinned }.toMutableList()

        val duplicateIdx = recent.indexOfFirst { isSameCoreParams(it.spec, spec) }
        if (duplicateIdx >= 0) {
            recent.removeAt(duplicateIdx)
        }
        // 如果已存在于永久保存中且核心参数一致，不重复塞入自动历史
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
        val trimmedRecent = recent.take(MAX_RECENT_AUTO)
        saveAll(context, pinned + trimmedRecent)
    }

    fun deleteById(context: Context, id: String) {
        val filtered = loadAll(context).filterNot { it.id == id }
        saveAll(context, filtered)
    }

    fun isSameCoreParams(a: NtcSpec, b: NtcSpec): Boolean {
        return abs(a.r25Ohm - b.r25Ohm) < 1e-6 &&
            abs(a.bValue - b.bValue) < 1e-4 &&
            abs(a.r25TolerancePct - b.r25TolerancePct) < 1e-4 &&
            abs(a.tMin - b.tMin) < 1e-4 &&
            abs(a.tMax - b.tMax) < 1e-4 &&
            abs(a.tStep - b.tStep) < 1e-4 &&
            a.includeBTolerance == b.includeBTolerance
    }
}
