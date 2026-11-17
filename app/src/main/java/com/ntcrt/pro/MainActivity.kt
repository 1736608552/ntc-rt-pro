package com.ntcrt.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.ntcrt.pro.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val presets = NtcMathEngine.builtInPresets()
    private var currentSpec: NtcSpec = presets.first()
    private var compareSpec: NtcSpec = presets.first().copy(
        id = "compare_b3950",
        label = "对比 B3950K",
        bValue = 3950.0
    )
    private var currentPoints: List<RtPoint> = emptyList()
    private var comparePoints: List<RtPoint> = emptyList()
    private lateinit var tableAdapter: RtTableAdapter
    private var isSliderSyncing = false
    private var ntcOnTop = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()
        setupPresetsBar()
        setupCurveControls()
        setupTableSection()
        setupToolsSection()

        applySpec(currentSpec)
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.pageCurve.visibility = if (item.itemId == R.id.nav_curve) View.VISIBLE else View.GONE
            binding.pageTable.visibility = if (item.itemId == R.id.nav_table) View.VISIBLE else View.GONE
            binding.pageTools.visibility = if (item.itemId == R.id.nav_tools) View.VISIBLE else View.GONE
            true
        }
    }

    private fun setupPresetsBar() {
        renderPresetPills()
    }

    private fun renderPresetPills() {
        binding.layoutPresets.removeAllViews()
        val density = resources.displayMetrics.density
        presets.forEach { preset ->
            val tv = TextView(this).apply {
                text = preset.label
                textSize = 11.5f
                val isSelected = preset.id == currentSpec.id
                setBackgroundResource(if (isSelected) R.drawable.bg_pill_active else R.drawable.bg_pill_idle)
                setTextColor(Color.parseColor(if (isSelected) "#00E5FF" else "#94A3B8"))
                val ph = (12 * density).toInt()
                val pv = (6 * density).toInt()
                setPadding(ph, pv, ph, pv)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * density).toInt()
                layoutParams = lp
                setOnClickListener {
                    applySpec(preset)
                }
            }
            binding.layoutPresets.addView(tv)
        }
    }

    private fun setupCurveControls() {
        binding.btnEditParams.setOnClickListener { showParamsDialog() }
        binding.btnExportPng.setOnClickListener { exportChartPng() }

        binding.ntcCurveView.onPointSelected = { primary, compare ->
            updateHud(primary, compare)
            val idx = binding.ntcCurveView.selectedIndex
            if (!isSliderSyncing && currentPoints.size > 1 && idx in currentPoints.indices) {
                isSliderSyncing = true
                binding.sliderTemp.value = idx.toFloat()
                isSliderSyncing = false
            }
        }

        binding.sliderTemp.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isSliderSyncing) {
                val idx = value.roundToInt().coerceIn(0, (currentPoints.size - 1).coerceAtLeast(0))
                if (idx in currentPoints.indices) {
                    isSliderSyncing = true
                    binding.ntcCurveView.selectTemperature(currentPoints[idx].tempC)
                    isSliderSyncing = false
                }
            }
        }

        binding.btnJump25.setOnClickListener {
            binding.ntcCurveView.selectTemperature(25.0)
        }

        binding.btnToggleScale.setOnClickListener {
            val next = !binding.ntcCurveView.useLogScale
            binding.ntcCurveView.useLogScale = next
            binding.btnToggleScale.text = if (next) "坐标: 对数Y" else "坐标: 线性"
        }

        binding.btnToggleBand.setOnClickListener {
            val next = !binding.ntcCurveView.showToleranceBand
            binding.ntcCurveView.showToleranceBand = next
            binding.btnToggleBand.text = if (next) "公差带: 开" else "公差带: 关"
        }

        binding.btnToggleCompare.setOnClickListener {
            if (!binding.ntcCurveView.showCompareCurve) {
                showCompareSelectorDialog()
            } else {
                binding.ntcCurveView.showCompareCurve = false
                binding.btnToggleCompare.text = "对比曲线: 关"
                binding.btnToggleCompare.setTextColor(Color.parseColor("#94A3B8"))
                binding.tvHudCompare.visibility = View.GONE
                binding.ntcCurveView.selectTemperature(
                    currentPoints.getOrNull(binding.ntcCurveView.selectedIndex)?.tempC ?: 25.0
                )
            }
        }
    }

    private fun showCompareSelectorDialog() {
        val options = arrayOf(
            "同 R25 对比 B=3950K",
            "同 R25 对比 B=3435K",
            "同 R25 对比 B=4300K",
            "自定义对比预设 (${presets[2].label})"
        )
        AlertDialog.Builder(this)
            .setTitle("选择叠加对比曲线")
            .setItems(options) { _, which ->
                compareSpec = when (which) {
                    0 -> currentSpec.copy(id = "cmp_3950", label = "B=3950K", bValue = 3950.0)
                    1 -> currentSpec.copy(id = "cmp_3435", label = "B=3435K", bValue = 3435.0)
                    2 -> currentSpec.copy(id = "cmp_4300", label = "B=4300K", bValue = 4300.0)
                    else -> presets[2].copy(
                        tMin = currentSpec.tMin,
                        tMax = currentSpec.tMax,
                        tStep = currentSpec.tStep
                    )
                }
                comparePoints = NtcMathEngine.generateTable(compareSpec)
                binding.ntcCurveView.showCompareCurve = true
                binding.ntcCurveView.setCurveData(currentPoints, comparePoints)
                binding.btnToggleCompare.text = "对比: 开"
                binding.btnToggleCompare.setTextColor(Color.parseColor("#F43F5E"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applySpec(spec: NtcSpec) {
        currentSpec = spec
        currentPoints = NtcMathEngine.generateTable(spec)
        compareSpec = compareSpec.copy(
            r25Ohm = if (compareSpec.id.startsWith("cmp_")) spec.r25Ohm else compareSpec.r25Ohm,
            tMin = spec.tMin,
            tMax = spec.tMax,
            tStep = spec.tStep
        )
        comparePoints = NtcMathEngine.generateTable(compareSpec)

        renderPresetPills()

        val tolLabel = if (spec.includeBTolerance) {
            "±${trimNum(spec.r25TolerancePct)}% + ΔB±${trimNum(spec.bTolerancePct)}%"
        } else {
            "±${trimNum(spec.r25TolerancePct)}%"
        }
        binding.tvTopSpecBadge.text =
            "R25=${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)} · B${spec.bValue.roundToInt()}K · $tolLabel"

        binding.tvSpecSummaryTitle.text = "${spec.manufacturer} · ${spec.partNumber}"
        val (rMin25, rMax25) = NtcMathEngine.resistanceBounds(25.0, spec)
        binding.tvSpecSummaryDetail.text =
            "温区: ${NtcMathEngine.formatTemp(spec.tMin)}℃ ～ ${NtcMathEngine.formatTemp(spec.tMax)}℃ (步进 ${trimNum(spec.tStep)}℃, 共 ${currentPoints.size} 点)\n" +
            "25℃ 基准: ${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)} (${NtcMathEngine.formatResistanceAuto(rMin25)} ～ ${NtcMathEngine.formatResistanceAuto(rMax25)})"

        // 安全配置 Slider 范围（索引驱动避免浮点步进异常）
        isSliderSyncing = true
        if (currentPoints.size > 1) {
            binding.sliderTemp.isEnabled = true
            binding.sliderTemp.valueFrom = 0f
            binding.sliderTemp.valueTo = (currentPoints.size - 1).toFloat()
            binding.sliderTemp.stepSize = 1f
            val defaultIdx = currentPoints.indices.minByOrNull { abs(currentPoints[it].tempC - 25.0) } ?: 0
            binding.sliderTemp.value = defaultIdx.toFloat()
        } else {
            binding.sliderTemp.isEnabled = false
        }
        isSliderSyncing = false

        binding.ntcCurveView.setCurveData(currentPoints, comparePoints)

        // 同步更新数据表
        val useKOhm = spec.r25Ohm >= 1000.0
        binding.tvTableUnitTag.text = if (useKOhm) "单位: kΩ" else "单位: Ω"
        tableAdapter.submitList(currentPoints, useKOhmUnit = useKOhm, highlightTemp = 25.0)

        // 同步更新工程工具默认参数
        refreshReverseCalculation()
        refreshDividerSimulation()
    }

    private fun updateHud(primary: RtPoint?, compare: RtPoint?) {
        if (primary == null) return
        binding.tvHudTemp.text = "${NtcMathEngine.formatTemp(primary.tempC)} ℃"
        binding.tvHudRNom.text = NtcMathEngine.formatResistanceAuto(primary.rNomOhm)
        binding.tvHudAlpha.text = String.format(Locale.US, "%.2f%%/℃", primary.alphaPctPerC)
        binding.tvHudRange.text =
            "允许区间: ${NtcMathEngine.formatResistanceAuto(primary.rMinOhm)} ～ ${NtcMathEngine.formatResistanceAuto(primary.rMaxOhm)}"

        if (compare != null && binding.ntcCurveView.showCompareCurve) {
            val diffPct = ((compare.rNomOhm - primary.rNomOhm) / primary.rNomOhm.coerceAtLeast(1e-9)) * 100.0
            binding.tvHudCompare.visibility = View.VISIBLE
            binding.tvHudCompare.text = String.format(
                Locale.US,
                "对比: %s (%+.2f%%)",
                NtcMathEngine.formatResistanceAuto(compare.rNomOhm),
                diffPct
            )
        } else {
            binding.tvHudCompare.visibility = View.GONE
        }
    }

    private fun showParamsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_params, null)
        val etMfg = view.findViewById<TextInputEditText>(R.id.etDlgManufacturer)
        val etPart = view.findViewById<TextInputEditText>(R.id.etDlgPartNumber)
        val etR25 = view.findViewById<TextInputEditText>(R.id.etDlgR25)
        val etB = view.findViewById<TextInputEditText>(R.id.etDlgBValue)
        val etTolR = view.findViewById<TextInputEditText>(R.id.etDlgTolR)
        val etTolB = view.findViewById<TextInputEditText>(R.id.etDlgTolB)
        val swB = view.findViewById<MaterialSwitch>(R.id.swDlgIncludeB)
        val etTMin = view.findViewById<TextInputEditText>(R.id.etDlgTMin)
        val etTMax = view.findViewById<TextInputEditText>(R.id.etDlgTMax)
        val etStep = view.findViewById<TextInputEditText>(R.id.etDlgTStep)

        etMfg.setText(currentSpec.manufacturer)
        etPart.setText(currentSpec.partNumber)
        etR25.setText(trimNum(currentSpec.r25Ohm))
        etB.setText(trimNum(currentSpec.bValue))
        etTolR.setText(trimNum(currentSpec.r25TolerancePct))
        etTolB.setText(trimNum(currentSpec.bTolerancePct))
        swB.isChecked = currentSpec.includeBTolerance
        etTMin.setText(trimNum(currentSpec.tMin))
        etTMax.setText(trimNum(currentSpec.tMax))
        etStep.setText(trimNum(currentSpec.tStep))

        AlertDialog.Builder(this)
            .setTitle("NTC 规格参数配置")
            .setView(view)
            .setPositiveButton("应用并重绘") { _, _ ->
                val r25 = etR25.text?.toString()?.toDoubleOrNull()
                val bVal = etB.text?.toString()?.toDoubleOrNull()
                val tolR = etTolR.text?.toString()?.toDoubleOrNull() ?: 0.0
                val tolB = etTolB.text?.toString()?.toDoubleOrNull() ?: 0.0
                val tMin = etTMin.text?.toString()?.toDoubleOrNull()
                val tMax = etTMax.text?.toString()?.toDoubleOrNull()
                val step = etStep.text?.toString()?.toDoubleOrNull()

                if (r25 == null || r25 <= 0.0 || bVal == null || bVal <= 0.0 ||
                    tMin == null || tMax == null || step == null || step <= 0.0 || tMin <= -273.0
                ) {
                    Toast.makeText(this, "请填写有效的正数参数与温度区间", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newSpec = currentSpec.copy(
                    id = "custom",
                    label = "自定义",
                    manufacturer = etMfg.text?.toString()?.trim()?.ifEmpty { "自定义 NTC" } ?: "自定义 NTC",
                    partNumber = etPart.text?.toString()?.trim()?.ifEmpty { "CUSTOM-NTC" } ?: "CUSTOM-NTC",
                    r25Ohm = r25,
                    bValue = bVal,
                    r25TolerancePct = tolR.coerceIn(0.0, 95.0),
                    bTolerancePct = tolB.coerceIn(0.0, 50.0),
                    includeBTolerance = swB.isChecked,
                    tMin = minOf(tMin, tMax),
                    tMax = maxOf(tMin, tMax),
                    tStep = step
                )
                applySpec(newSpec)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupTableSection() {
        tableAdapter = RtTableAdapter { point ->
            // 点击表格行跳转到曲线工作台并锁定该温度点
            binding.bottomNav.selectedItemId = R.id.nav_curve
            binding.ntcCurveView.selectTemperature(point.tempC)
        }
        binding.rvRtTable.layoutManager = LinearLayoutManager(this)
        binding.rvRtTable.adapter = tableAdapter

        binding.btnTableLocate.setOnClickListener {
            val target = binding.etTableSearchTemp.text?.toString()?.toDoubleOrNull()
            if (target == null || currentPoints.isEmpty()) {
                Toast.makeText(this, "请输入有效的温度数值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val idx = currentPoints.indices.minByOrNull { abs(currentPoints[it].tempC - target) } ?: return@setOnClickListener
            val matched = currentPoints[idx]
            tableAdapter.setHighlightTemp(matched.tempC)
            (binding.rvRtTable.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 60)
            Toast.makeText(
                this,
                "已定位 ${NtcMathEngine.formatTemp(matched.tempC)}℃: ${NtcMathEngine.formatResistanceAuto(matched.rNomOhm)}",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnExportCsv.setOnClickListener { exportCsvFile() }
        binding.btnCopyMd.setOnClickListener {
            copyToClipboard("NTC_RT_Markdown", NtcMathEngine.exportMarkdown(currentSpec, currentPoints))
            Toast.makeText(this, "已复制 Markdown 特性表", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopyCLut.setOnClickListener {
            copyToClipboard("NTC_LUT_C", NtcMathEngine.exportCLookupTable(currentSpec, currentPoints))
            Toast.makeText(this, "已复制嵌入式 C 语言查表代码", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupToolsSection() {
        binding.etToolResInput.doAfterTextChanged { refreshReverseCalculation() }
        binding.etToolTempInput.doAfterTextChanged { refreshReverseCalculation() }

        val bWatcher: () -> Unit = { refreshSolvedB() }
        binding.etSolveT1.doAfterTextChanged { bWatcher() }
        binding.etSolveR1.doAfterTextChanged { bWatcher() }
        binding.etSolveT2.doAfterTextChanged { bWatcher() }
        binding.etSolveR2.doAfterTextChanged { bWatcher() }

        binding.btnApplySolvedB.setOnClickListener {
            val bVal = computeCurrentSolvedB()
            if (bVal != null && bVal > 0) {
                applySpec(currentSpec.copy(id = "custom_b", bValue = (bVal * 10.0).roundToInt() / 10.0))
                binding.bottomNav.selectedItemId = R.id.nav_curve
                Toast.makeText(this, "已将 B=${bVal.roundToInt()}K 应用到特性曲线", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请检查两点温度和阻值输入", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleDividerPos.setOnClickListener {
            ntcOnTop = !ntcOnTop
            binding.btnToggleDividerPos.text = if (ntcOnTop) "NTC 上拉" else "NTC 下拉"
            refreshDividerSimulation()
        }

        binding.etDivVcc.doAfterTextChanged { refreshDividerSimulation() }
        binding.etDivRref.doAfterTextChanged { refreshDividerSimulation() }
        binding.etDivAdcBits.doAfterTextChanged { refreshDividerSimulation() }

        binding.voltageCurveView.onPointSelected = { dp ->
            if (dp != null) {
                binding.tvDivHud.text = String.format(
                    Locale.US,
                    "%s℃ → R=%s · Vout=%.3fV · ADC=%d",
                    NtcMathEngine.formatTemp(dp.tempC),
                    NtcMathEngine.formatResistanceAuto(dp.rNtcOhm),
                    dp.vOut,
                    dp.adcCode
                )
            }
        }

        refreshSolvedB()
        refreshReverseCalculation()
        refreshDividerSimulation()
    }

    private fun refreshReverseCalculation() {
        val rInput = binding.etToolResInput.text?.toString()?.toDoubleOrNull()
        val tInput = binding.etToolTempInput.text?.toString()?.toDoubleOrNull()
        val sb = StringBuilder()

        if (rInput != null && rInput > 0.0) {
            try {
                val res = NtcMathEngine.reverseSolveTemperature(rInput, currentSpec)
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "[阻值→温度] R=%s ⇒ T = %.2f ℃",
                        NtcMathEngine.formatResistanceAuto(rInput),
                        res.tempNomC
                    )
                )
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "  公差温度带: %.2f℃ ～ %.2f℃ (α=%.2f%%/℃)",
                        res.tempLowC, res.tempHighC, res.alphaPctPerC
                    )
                )
            } catch (e: Exception) {
                sb.appendLine("[阻值→温度] 输入超出物理范围")
            }
        } else {
            sb.appendLine("[阻值→温度] 请输入大于 0 的阻值")
        }

        if (tInput != null && tInput > -273.0) {
            val rNom = NtcMathEngine.nominalResistance(tInput, currentSpec.r25Ohm, currentSpec.bValue)
            val (rMin, rMax) = NtcMathEngine.resistanceBounds(tInput, currentSpec)
            sb.append(
                String.format(
                    Locale.US,
                    "[温度→阻值] T=%s℃ ⇒ %s (%s ～ %s)",
                    NtcMathEngine.formatTemp(tInput),
                    NtcMathEngine.formatResistanceAuto(rNom),
                    NtcMathEngine.formatResistanceAuto(rMin),
                    NtcMathEngine.formatResistanceAuto(rMax)
                )
            )
        } else {
            sb.append("[温度→阻值] 请输入有效温度")
        }

        binding.tvToolReverseOutput.text = sb.toString()
    }

    private fun computeCurrentSolvedB(): Double? {
        val t1 = binding.etSolveT1.text?.toString()?.toDoubleOrNull() ?: return null
        val r1 = binding.etSolveR1.text?.toString()?.toDoubleOrNull() ?: return null
        val t2 = binding.etSolveT2.text?.toString()?.toDoubleOrNull() ?: return null
        val r2 = binding.etSolveR2.text?.toString()?.toDoubleOrNull() ?: return null
        if (abs(t1 - t2) < 1e-4 || r1 <= 0.0 || r2 <= 0.0) return null
        return NtcMathEngine.solveBValue(t1, r1, t2, r2)
    }

    private fun refreshSolvedB() {
        val bVal = computeCurrentSolvedB()
        binding.tvSolvedBResult.text = if (bVal != null && bVal > 0) {
            String.format(Locale.US, "估算 B 值: %.1f K", bVal)
        } else {
            "估算 B 值: 输入无效（温度需不同且阻值 > 0）"
        }
    }

    private fun refreshDividerSimulation() {
        val vcc = binding.etDivVcc.text?.toString()?.toDoubleOrNull()?.takeIf { it > 0 } ?: 3.3
        val rRef = binding.etDivRref.text?.toString()?.toDoubleOrNull()?.takeIf { it > 0 } ?: currentSpec.r25Ohm
        val bits = binding.etDivAdcBits.text?.toString()?.toIntOrNull()?.coerceIn(8, 16) ?: 12
        val data = NtcMathEngine.simulateDivider(currentSpec, vcc, rRef, ntcOnTop, bits)
        binding.voltageCurveView.setDividerData(data, vcc)
    }

    private fun exportChartPng() {
        try {
            val bmp = binding.ntcCurveView.renderExportBitmap(
                currentSpec,
                if (binding.ntcCurveView.showCompareCurve) compareSpec else null
            )
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "NTC_RT_Curve_${currentSpec.partNumber.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            shareExportFile(file, "image/png", "分享 NTC R-T 特性曲线图")
        } catch (e: Exception) {
            Toast.makeText(this, "导出曲线图失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportCsvFile() {
        try {
            val csv = NtcMathEngine.exportCsv(currentSpec, currentPoints)
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "NTC_RT_Table_${currentSpec.partNumber.replace(Regex("[^A-Za-z0-9._-]"), "_")}.csv")
            file.writeText(csv, Charsets.UTF_8)
            shareExportFile(file, "text/csv", "分享 NTC 温度阻值特性表 (CSV)")
        } catch (e: Exception) {
            Toast.makeText(this, "导出 CSV 失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareExportFile(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun copyToClipboard(label: String, content: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, content))
    }

    private fun trimNum(v: Double): String {
        return if (abs(v - v.roundToInt()) < 1e-6) {
            v.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }
}
