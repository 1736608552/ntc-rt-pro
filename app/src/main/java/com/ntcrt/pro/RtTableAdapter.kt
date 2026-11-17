package com.ntcrt.pro

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.abs

/**
 * 温度-阻值特性表 RecyclerView 适配器
 */
class RtTableAdapter(
    private val onRowClick: (RtPoint) -> Unit
) : RecyclerView.Adapter<RtTableAdapter.RowViewHolder>() {

    private var items: List<RtPoint> = emptyList()
    private var divideBy1000: Boolean = false
    private var highlightTempC: Double? = 25.0

    fun submitList(newItems: List<RtPoint>, useKOhmUnit: Boolean, highlightTemp: Double? = 25.0) {
        items = newItems
        divideBy1000 = useKOhmUnit
        highlightTempC = highlightTemp
        notifyDataSetChanged()
    }

    fun setHighlightTemp(tempC: Double?) {
        highlightTempC = tempC
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rt_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val item = items[position]
        val isHighlighted = highlightTempC != null && abs(item.tempC - highlightTempC!!) < 1e-3
        val is25C = abs(item.tempC - 25.0) < 1e-3

        holder.tvTemp.text = "${NtcMathEngine.formatTemp(item.tempC)}℃"
        holder.tvMin.text = NtcMathEngine.formatResistanceCompact(item.rMinOhm, divideBy1000)
        holder.tvNom.text = NtcMathEngine.formatResistanceCompact(item.rNomOhm, divideBy1000)
        holder.tvMax.text = NtcMathEngine.formatResistanceCompact(item.rMaxOhm, divideBy1000)
        holder.tvAlpha.text = String.format(Locale.US, "%.2f%%", item.alphaPctPerC)

        when {
            isHighlighted -> {
                holder.itemView.setBackgroundColor(Color.parseColor("#1A00E5FF"))
                holder.tvTemp.setTextColor(Color.parseColor("#00E5FF"))
                holder.tvNom.setTextColor(Color.parseColor("#FFFFFF"))
            }
            is25C -> {
                holder.itemView.setBackgroundColor(Color.parseColor("#1AF59E0B"))
                holder.tvTemp.setTextColor(Color.parseColor("#F59E0B"))
                holder.tvNom.setTextColor(Color.parseColor("#FDE68A"))
            }
            position % 2 == 0 -> {
                holder.itemView.setBackgroundColor(Color.parseColor("#0B111E"))
                holder.tvTemp.setTextColor(Color.parseColor("#E2E8F0"))
                holder.tvNom.setTextColor(Color.parseColor("#00E5FF"))
            }
            else -> {
                holder.itemView.setBackgroundColor(Color.parseColor("#101726"))
                holder.tvTemp.setTextColor(Color.parseColor("#E2E8F0"))
                holder.tvNom.setTextColor(Color.parseColor("#00E5FF"))
            }
        }

        holder.itemView.setOnClickListener { onRowClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTemp: TextView = view.findViewById(R.id.tvRowTemp)
        val tvMin: TextView = view.findViewById(R.id.tvRowMin)
        val tvNom: TextView = view.findViewById(R.id.tvRowNom)
        val tvMax: TextView = view.findViewById(R.id.tvRowMax)
        val tvAlpha: TextView = view.findViewById(R.id.tvRowAlpha)
    }
}
