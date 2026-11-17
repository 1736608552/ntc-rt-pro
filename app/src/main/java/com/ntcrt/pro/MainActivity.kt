package com.ntcrt.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.ntcrt.pro.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentSpec: NtcSpec = NtcMathEngine.defaultSpec()
    private var compareSpec: NtcSpec = currentSpec.copy(bValue = 3950.0)
    private var currentPoints: List<RtPoint> = emptyList()
    private var comparePoints: List<RtPoint> = emptyList()
    private lateinit var tableAdapter: RtTableAdapter

    private var isSliderSyncing = false
    private var isQuickSyncing = false
    private var ntcOnTop = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoApplyRunnable = Runnable { applyFromQuickInputs(hideKeyboard = false, silentError = true) }
    private val snapshotRunnable = Runnable { SavedCurveStore.recordRecentSnapshot(this, currentSpec) }
    private var cachedUpdateInfo: RemoteUpdateInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentSpec = loadSavedSpec()
        compareSpec = currentSpec.copy(bValue = 3950.0)

        setupBottomNav()
        setupQuickInputBar()
        setupCurveControls()
        setupTableSection()
        setupToolsSection()
        setupUpdateFeature()

        syncQuickInputsFromSpec(currentSpec)
        applySpec(currentSpec)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoApplyRunnable)
        mainHandler.removeCallbacks(snapshotRunnable)
        super.onDestroy()
    }

    private fun loadSavedSpec(): NtcSpec {
        val sp = getSharedPreferences("ntc_spec_prefs", MODE_PRIVATE)
        if (!sp.contains("r25")) return NtcMathEngine.defaultSpec()
        val legacyRTol = sp.getFloat("tolR", 17.0f).toDouble()
        val legacyIncB = sp.getBoolean("includeB", false)
        val legacyBTol = if (legacyIncB) sp.getFloat("tolB", 0.0f).toDouble() else 0.0
        return NtcSpec(
            manufacturer = sp.getString("manufacturer", "") ?: "",
            partNumber = sp.getString("partNumber", "") ?: "",
            r25Ohm = sp.getFloat("r25", 5.0f).toDouble(),
            bValue = sp.getFloat("bValue", 4100.0f).toDouble(),
            r25TolPlusPct = sp.getFloat("tolRPlus", legacyRTol.toFloat()).toDouble(),
            r25TolMinusPct = sp.getFloat("tolRMinus", legacyRTol.toFloat()).toDouble(),
            bTolPlusPct = sp.getFloat("tolBPlus", legacyBTol.toFloat()).toDouble(),
            bTolMinusPct = sp.getFloat("tolBMinus", legacyBTol.toFloat()).toDouble(),
            tMin = sp.getFloat("tMin", 20.0f).toDouble(),
            tMax = sp.getFloat("tMax", 36.5f).toDouble(),
            tStep = sp.getFloat("tStep", 0.5f).toDouble()
        )
    }

    private fun saveSpec(spec: NtcSpec) {
        getSharedPreferences("ntc_spec_prefs", MODE_PRIVATE).edit()
            .putString("manufacturer", spec.manufacturer)
            .putString("partNumber", spec.partNumber)
            .putFloat("r25", spec.r25Ohm.toFloat())
            .putFloat("bValue", spec.bValue.toFloat())
            .putFloat("tolRPlus", spec.r25TolPlusPct.toFloat())
            .putFloat("tolRMinus", spec.r25TolMinusPct.toFloat())
            .putFloat("tolBPlus", spec.bTolPlusPct.toFloat())
            .putFloat("tolBMinus", spec.bTolMinusPct.toFloat())
            .putFloat("tMin", spec.tMin.toFloat())
            .putFloat("tMax", spec.tMax.toFloat())
            .putFloat("tStep", spec.tStep.toFloat())
            .apply()
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.pageCurve.visibility = if (item.itemId == R.id.nav_curve) View.VISIBLE else View.GONE
            binding.pageTable.visibility = if (item.itemId == R.id.nav_table) View.VISIBLE else View.GONE
            binding.pageTools.visibility = if (item.itemId == R.id.nav_tools) View.VISIBLE else View.GONE
            true
        }
    }

    private fun setupQuickInputBar() {
        val scheduleAutoApply: () -> Unit = {
            if (!isQuickSyncing) {
                mainHandler.removeCallbacks(autoApplyRunnable)
                mainHandler.postDelayed(autoApplyRunnable, 420)
            }
        }
        binding.etQuickR25.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickB.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickRPlus.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickRMinus.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickBPlus.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickBMinus.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickTMin.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickTMax.doAfterTextChanged { scheduleAutoApply() }
        binding.etQuickStep.doAfterTextChanged { scheduleAutoApply() }

        binding.btnQuickApply.setOnClickListener {
            mainHandler.removeCallbacks(autoApplyRunnable)
            applyFromQuickInputs(hideKeyboard = true, silentError = false)
        }
    }

    private fun syncQuickInputsFromSpec(spec: NtcSpec) {
        isQuickSyncing = true
        binding.etQuickR25.setText(trimNum(spec.r25Ohm))
        binding.etQuickB.setText(trimNum(spec.bValue))
        binding.etQuickRPlus.setText(trimNum(spec.r25TolPlusPct))
        binding.etQuickRMinus.setText(trimNum(spec.r25TolMinusPct))
        binding.etQuickBPlus.setText(trimNum(spec.bTolPlusPct))
        binding.etQuickBMinus.setText(trimNum(spec.bTolMinusPct))
        binding.etQuickTMin.setText(trimNum(spec.tMin))
        binding.etQuickTMax.setText(trimNum(spec.tMax))
        binding.etQuickStep.setText(trimNum(spec.tStep))
        isQuickSyncing = false
    }

    private fun applyFromQuickInputs(hideKeyboard: Boolean, silentError: Boolean) {
        val r25 = binding.etQuickR25.text?.toString()?.toDoubleOrNull()
        val bVal = binding.etQuickB.text?.toString()?.toDoubleOrNull()
        val rPlus = abs(binding.etQuickRPlus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 500.0)
        val rMinus = abs(binding.etQuickRMinus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 99.9)
        val bPlus = abs(binding.etQuickBPlus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 200.0)
        val bMinus = abs(binding.etQuickBMinus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 99.9)
        val tMin = binding.etQuickTMin.text?.toString()?.toDoubleOrNull()
        val tMax = binding.etQuickTMax.text?.toString()?.toDoubleOrNull()
        val step = binding.etQuickStep.text?.toString()?.toDoubleOrNull()

        if (r25 == null || r25 <= 0.0 || bVal == null || bVal <= 0.0 ||
            tMin == null || tMax == null || step == null || step <= 0.0 || tMin <= -273.0
        ) {
            if (!silentError) {
                Toast.makeText(this, "请检查 R25、B 值、温区与步进输入", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val newSpec = currentSpec.copy(
            r25Ohm = r25,
            bValue = bVal,
            r25TolPlusPct = rPlus,
            r25TolMinusPct = rMinus,
            bTolPlusPct = bPlus,
            bTolMinusPct = bMinus,
            tMin = minOf(tMin, tMax),
            tMax = maxOf(tMin, tMax),
            tStep = step
        )
        applySpec(newSpec)
        if (hideKeyboard) {
            currentFocus?.let { v ->
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
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
            binding.btnToggleScale.text = if (next) "坐标:对数" else "坐标:线性"
        }

        binding.btnToggleBand.setOnClickListener {
            val next = !binding.ntcCurveView.showToleranceBand
            binding.ntcCurveView.showToleranceBand = next
            binding.btnToggleBand.text = if (next) "公差:开" else "公差:关"
        }

        binding.btnSaveCurve.setOnClickListener {
            showSaveCurveDialog()
        }

        binding.btnToggleCompare.setOnClickListener {
            if (!binding.ntcCurveView.showCompareCurve) {
                showCompareSelectorDialog()
            } else {
                binding.ntcCurveView.showCompareCurve = false
                binding.btnToggleCompare.text = "对比:关"
                binding.btnToggleCompare.setTextColor(Color.parseColor("#94A3B8"))
                binding.tvHudCompare.visibility = View.GONE
                binding.ntcCurveView.selectTemperature(
                    currentPoints.getOrNull(binding.ntcCurveView.selectedIndex)?.tempC ?: 25.0
                )
            }
        }
    }

    private fun showSaveCurveDialog(onSaved: (() -> Unit)? = null) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etName = TextInputEditText(this).apply {
            hint = "留空则自动命名：${SavedCurveStore.defaultTitleFor(currentSpec)}"
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 13.5f
        }
        container.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("保存当前计算曲线")
            .setMessage("保存后可在「对比」列表中随时调出叠加比对或重新载入。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val rec = SavedCurveStore.savePinned(this, currentSpec, etName.text?.toString() ?: "")
                Toast.makeText(this, "已保存：${rec.title}", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun enableCompareSpec(targetSpec: NtcSpec) {
        compareSpec = targetSpec.copy(
            tMin = currentSpec.tMin,
            tMax = currentSpec.tMax,
            tStep = currentSpec.tStep
        )
        comparePoints = NtcMathEngine.generateTable(compareSpec)
        binding.ntcCurveView.showCompareCurve = true
        binding.ntcCurveView.setCurveData(currentPoints, comparePoints)
        binding.btnToggleCompare.text = "对比:开"
        binding.btnToggleCompare.setTextColor(Color.parseColor("#F43F5E"))
    }

    private fun showCompareSelectorDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_compare_records, null)
        val btnSaveCur = view.findViewById<View>(R.id.btnDlgSaveCurrent)
        val etCustomR25 = view.findViewById<TextInputEditText>(R.id.etCmpCustomR25)
        val etCustomB = view.findViewById<TextInputEditText>(R.id.etCmpCustomB)
        val btnApplyCustom = view.findViewById<View>(R.id.btnCmpApplyCustom)
        val tvEmpty = view.findViewById<TextView>(R.id.tvSavedRecordsEmpty)
        val listLayout = view.findViewById<LinearLayout>(R.id.layoutSavedRecordsList)

        etCustomR25.setText(trimNum(compareSpec.r25Ohm))
        etCustomB.setText(trimNum(compareSpec.bValue))

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择或管理对比曲线")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()

        val density = resources.displayMetrics.density

        fun renderRecords() {
            listLayout.removeAllViews()
            val records = SavedCurveStore.loadAll(this)
            tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

            records.forEach { rec ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_hud_card)
                    val p = (10 * density).toInt()
                    setPadding(p, p, p, p)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = (8 * density).toInt()
                    layoutParams = lp
                }

                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tagView = TextView(this).apply {
                    text = if (rec.isPinned) "已存" else "历史"
                    textSize = 10f
                    setBackgroundResource(if (rec.isPinned) R.drawable.bg_pill_active else R.drawable.bg_pill_idle)
                    setTextColor(Color.parseColor(if (rec.isPinned) "#00E5FF" else "#94A3B8"))
                    setPadding((6 * density).toInt(), (1 * density).toInt(), (6 * density).toInt(), (1 * density).toInt())
                }
                val titleView = TextView(this).apply {
                    text = "  ${rec.title}"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#F8FAFC"))
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val timeView = TextView(this).apply {
                    text = rec.formattedTime()
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#64748B"))
                }
                topRow.addView(tagView)
                topRow.addView(titleView)
                topRow.addView(timeView)

                val summaryView = TextView(this).apply {
                    text = rec.summaryLine()
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#94A3B8"))
                    val mt = (4 * density).toInt()
                    setPadding(0, mt, 0, (6 * density).toInt())
                }

                val actionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                }

                fun makeActionChip(label: String, colorHex: String, onClick: () -> Unit): TextView {
                    return TextView(this).apply {
                        text = label
                        textSize = 11f
                        setTextColor(Color.parseColor(colorHex))
                        setBackgroundResource(R.drawable.bg_pill_idle)
                        setPadding((9 * density).toInt(), (3 * density).toInt(), (9 * density).toInt(), (3 * density).toInt())
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.marginStart = (6 * density).toInt()
                        layoutParams = lp
                        setOnClickListener { onClick() }
                    }
                }

                if (!rec.isPinned) {
                    actionRow.addView(makeActionChip("收藏", "#F59E0B") {
                        SavedCurveStore.savePinned(this, rec.spec, rec.title)
                        renderRecords()
                    })
                }
                actionRow.addView(makeActionChip("载入", "#10B981") {
                    syncQuickInputsFromSpec(rec.spec)
                    applySpec(rec.spec)
                    dialog.dismiss()
                    Toast.makeText(this, "已载入为主曲线", Toast.LENGTH_SHORT).show()
                })
                actionRow.addView(makeActionChip("删除", "#94A3B8") {
                    SavedCurveStore.deleteById(this, rec.id)
                    renderRecords()
                })
                actionRow.addView(makeActionChip("设为对比", "#F43F5E") {
                    enableCompareSpec(rec.spec)
                    dialog.dismiss()
                })

                card.addView(topRow)
                card.addView(summaryView)
                card.addView(actionRow)
                card.setOnClickListener {
                    enableCompareSpec(rec.spec)
                    dialog.dismiss()
                }
                listLayout.addView(card)
            }
        }

        btnSaveCur.setOnClickListener {
            showSaveCurveDialog { renderRecords() }
        }

        btnApplyCustom.setOnClickListener {
            val r25 = etCustomR25.text?.toString()?.toDoubleOrNull()
            val bVal = etCustomB.text?.toString()?.toDoubleOrNull()
            if (r25 == null || r25 <= 0.0 || bVal == null || bVal <= 0.0) {
                Toast.makeText(this, "请输入有效的对比 R25 和 B 值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enableCompareSpec(currentSpec.copy(r25Ohm = r25, bValue = bVal))
            dialog.dismiss()
        }

        renderRecords()
        dialog.show()
    }

    private fun applySpec(spec: NtcSpec) {
        currentSpec = spec
        saveSpec(spec)
        mainHandler.removeCallbacks(snapshotRunnable)
        mainHandler.postDelayed(snapshotRunnable, 1500)

        currentPoints = NtcMathEngine.generateTable(spec)
        compareSpec = compareSpec.copy(
            tMin = spec.tMin,
            tMax = spec.tMax,
            tStep = spec.tStep
        )
        comparePoints = NtcMathEngine.generateTable(compareSpec)

        val rTolLabel = NtcMathEngine.formatTolPair(spec.r25TolPlusPct, spec.r25TolMinusPct)
        val bTolLabel = NtcMathEngine.formatTolPair(spec.bTolPlusPct, spec.bTolMinusPct)
        binding.tvTopSpecBadge.text =
            "R25=${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)}($rTolLabel) · B${spec.bValue.roundToInt()}K($bTolLabel)"

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

        val useKOhm = spec.r25Ohm >= 1000.0
        binding.tvTableUnitTag.text = if (useKOhm) "单位: kΩ" else "单位: Ω"
        tableAdapter.submitList(currentPoints, useKOhmUnit = useKOhm, highlightTemp = 25.0)

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
        val etTolRPlus = view.findViewById<TextInputEditText>(R.id.etDlgTolRPlus)
        val etTolRMinus = view.findViewById<TextInputEditText>(R.id.etDlgTolRMinus)
        val etTolBPlus = view.findViewById<TextInputEditText>(R.id.etDlgTolBPlus)
        val etTolBMinus = view.findViewById<TextInputEditText>(R.id.etDlgTolBMinus)
        val etTMin = view.findViewById<TextInputEditText>(R.id.etDlgTMin)
        val etTMax = view.findViewById<TextInputEditText>(R.id.etDlgTMax)
        val etStep = view.findViewById<TextInputEditText>(R.id.etDlgTStep)

        etMfg.setText(currentSpec.manufacturer)
        etPart.setText(currentSpec.partNumber)
        etR25.setText(trimNum(currentSpec.r25Ohm))
        etB.setText(trimNum(currentSpec.bValue))
        etTolRPlus.setText(trimNum(currentSpec.r25TolPlusPct))
        etTolRMinus.setText(trimNum(currentSpec.r25TolMinusPct))
        etTolBPlus.setText(trimNum(currentSpec.bTolPlusPct))
        etTolBMinus.setText(trimNum(currentSpec.bTolMinusPct))
        etTMin.setText(trimNum(currentSpec.tMin))
        etTMax.setText(trimNum(currentSpec.tMax))
        etStep.setText(trimNum(currentSpec.tStep))

        AlertDialog.Builder(this)
            .setTitle("高级参数与导出备注")
            .setView(view)
            .setPositiveButton("应用并重绘") { _, _ ->
                val r25 = etR25.text?.toString()?.toDoubleOrNull()
                val bVal = etB.text?.toString()?.toDoubleOrNull()
                val rPlus = abs(etTolRPlus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 500.0)
                val rMinus = abs(etTolRMinus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 99.9)
                val bPlus = abs(etTolBPlus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 200.0)
                val bMinus = abs(etTolBMinus.text?.toString()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 99.9)
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
                    manufacturer = etMfg.text?.toString()?.trim() ?: "",
                    partNumber = etPart.text?.toString()?.trim() ?: "",
                    r25Ohm = r25,
                    bValue = bVal,
                    r25TolPlusPct = rPlus,
                    r25TolMinusPct = rMinus,
                    bTolPlusPct = bPlus,
                    bTolMinusPct = bMinus,
                    tMin = minOf(tMin, tMax),
                    tMax = maxOf(tMin, tMax),
                    tStep = step
                )
                syncQuickInputsFromSpec(newSpec)
                applySpec(newSpec)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 多 CDN 在线更新逻辑 ====================
    private fun setupUpdateFeature() {
        binding.btnCheckUpdate.text = "v${BuildConfig.VERSION_NAME}"
        binding.btnCheckUpdate.setOnClickListener {
            triggerManualUpdateCheck()
        }
        // 启动 1.5 秒后静默检查一次新版本
        mainHandler.postDelayed({
            AppUpdater.checkForUpdates(
                onSuccess = { info ->
                    cachedUpdateInfo = info
                    if (info.hasNewVersion) {
                        binding.btnCheckUpdate.text = "新版 v${info.versionName}"
                        binding.btnCheckUpdate.setTextColor(Color.parseColor("#10B981"))
                        binding.btnCheckUpdate.setBackgroundResource(R.drawable.bg_pill_active)
                        showUpdateDialog(info)
                    }
                },
                onError = { /* 静默检查失败不打扰用户 */ }
            )
        }, 1500)
    }

    private fun triggerManualUpdateCheck() {
        Toast.makeText(this, "正在多节点测速检查更新...", Toast.LENGTH_SHORT).show()
        AppUpdater.checkForUpdates(
            onSuccess = { info ->
                cachedUpdateInfo = info
                if (info.hasNewVersion) {
                    binding.btnCheckUpdate.text = "新版 v${info.versionName}"
                    binding.btnCheckUpdate.setTextColor(Color.parseColor("#10B981"))
                }
                showUpdateDialog(info)
            },
            onError = { msg ->
                Toast.makeText(this, "检测失败: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun showUpdateDialog(info: RemoteUpdateInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        val tvBadge = view.findViewById<TextView>(R.id.tvUpdateBadge)
        val tvVer = view.findViewById<TextView>(R.id.tvUpdateVersion)
        val tvSize = view.findViewById<TextView>(R.id.tvUpdateSize)
        val tvNotes = view.findViewById<TextView>(R.id.tvUpdateNotes)
        val layoutMirrors = view.findViewById<LinearLayout>(R.id.layoutCdnMirrors)
        val layoutProgress = view.findViewById<LinearLayout>(R.id.layoutDownloadProgress)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressUpdateApk)
        val tvProgress = view.findViewById<TextView>(R.id.tvUpdateProgressText)

        if (info.hasNewVersion) {
            tvBadge.text = "发现新版"
            tvBadge.setTextColor(Color.parseColor("#10B981"))
            tvVer.text = "v${BuildConfig.VERSION_NAME} → v${info.versionName}"
        } else {
            tvBadge.text = "已是最新"
            tvBadge.setTextColor(Color.parseColor("#00E5FF"))
            tvVer.text = "当前 v${BuildConfig.VERSION_NAME} (最新 v${info.versionName})"
        }
        tvSize.text = AppUpdater.formatSize(info.apkSize)
        tvNotes.text = info.releaseNotes.ifBlank { "常规性能优化与体验改进。" }

        var selectedMirror = info.mirrors.firstOrNull { it.available } ?: info.mirrors.first()
        val density = resources.displayMetrics.density

        fun renderMirrorOptions() {
            layoutMirrors.removeAllViews()
            info.mirrors.forEach { m ->
                val isSelected = m.id == selectedMirror.id
                val row = TextView(this).apply {
                    val latencyTxt = if (m.available && m.latencyMs > 0) "${m.latencyMs} ms" else "备用/直连"
                    text = "${if (isSelected) "●" else "○"}  ${m.name}   [$latencyTxt]"
                    textSize = 12f
                    setBackgroundResource(if (isSelected) R.drawable.bg_pill_active else R.drawable.bg_pill_idle)
                    setTextColor(Color.parseColor(if (isSelected) "#00E5FF" else "#94A3B8"))
                    val ph = (12 * density).toInt()
                    val pv = (7 * density).toInt()
                    setPadding(ph, pv, ph, pv)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = (6 * density).toInt()
                    layoutParams = lp
                    setOnClickListener {
                        selectedMirror = m
                        renderMirrorOptions()
                    }
                }
                layoutMirrors.addView(row)
            }
        }
        renderMirrorOptions()

        val cancelFlag = AtomicBoolean(false)
        val actionLabel = if (info.hasNewVersion) "立即加速更新" else "重新下载安装"

        val dialog = AlertDialog.Builder(this)
            .setTitle("在线更新 · 多 CDN 加速")
            .setView(view)
            .setPositiveButton(actionLabel, null)
            .setNegativeButton("关闭") { _, _ ->
                cancelFlag.set(true)
            }
            .create()

        dialog.setOnShowListener {
            val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            posBtn.setOnClickListener {
                posBtn.isEnabled = false
                posBtn.text = "正在下载..."
                layoutProgress.visibility = View.VISIBLE
                progressBar.progress = 0
                tvProgress.text = "正在连接 ${selectedMirror.name}..."

                AppUpdater.downloadApkWithFallback(
                    context = this,
                    info = info,
                    preferredMirror = selectedMirror,
                    cancelFlag = cancelFlag,
                    onMirrorSwitched = { m ->
                        selectedMirror = m
                        renderMirrorOptions()
                        tvProgress.text = "正在使用 ${m.name} 拉取安装包..."
                    },
                    onProgress = { pct, cur, tot, spd ->
                        progressBar.progress = pct
                        val curMb = cur / (1024.0 * 1024.0)
                        val totMb = tot.coerceAtLeast(1L) / (1024.0 * 1024.0)
                        tvProgress.text = String.format(
                            Locale.US,
                            "%s · %d%% (%.2f / %.2f MB) · %s",
                            selectedMirror.name, pct, curMb, totMb, spd
                        )
                    },
                    onComplete = { apkFile ->
                        tvProgress.text = "下载并校验完成，正在拉起系统安装..."
                        val started = AppUpdater.installApk(this, apkFile)
                        if (!started) {
                            Toast.makeText(this, "请授予“安装未知应用”权限后重试", Toast.LENGTH_LONG).show()
                        }
                        dialog.dismiss()
                    },
                    onError = { err ->
                        posBtn.isEnabled = true
                        posBtn.text = "重试下载"
                        tvProgress.text = "下载失败: $err"
                    }
                )
            }
        }
        dialog.show()
    }

    // ==================== 表格与导出 ====================
    private fun setupTableSection() {
        tableAdapter = RtTableAdapter { point ->
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

    // ==================== 工程反推与分压仿真 ====================
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
                val updated = currentSpec.copy(bValue = (bVal * 10.0).roundToInt() / 10.0)
                syncQuickInputsFromSpec(updated)
                applySpec(updated)
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
            val safeTag = currentSpec.partNumber.ifBlank { "R${trimNum(currentSpec.r25Ohm)}_B${currentSpec.bValue.roundToInt()}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "NTC_RT_Curve_${safeTag}.png")
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
            val safeTag = currentSpec.partNumber.ifBlank { "R${trimNum(currentSpec.r25Ohm)}_B${currentSpec.bValue.roundToInt()}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "NTC_RT_Table_${safeTag}.csv")
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
