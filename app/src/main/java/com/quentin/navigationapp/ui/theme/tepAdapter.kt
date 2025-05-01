package com.quentin.navigationapp.ui.theme

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.quentin.navigationapp.R
import com.quentin.navigationapp.model.Step

class StepAdapter(private val steps: List<Step>) : RecyclerView.Adapter<StepAdapter.StepViewHolder>() {

    class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvInstruction: TextView = view.findViewById(R.id.tvInstruction)
        val tvDetail: TextView = view.findViewById(R.id.tvDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_step, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = steps[position]
        holder.tvInstruction.text = step.instruction
        holder.tvDetail.text = "${step.distance.toInt()} m, ${step.duration.toInt()} s"
        // Choisir l'icône en fonction du type
        val iconRes = when (step.type) {
            1 -> R.drawable.ic_turn_right
            11 -> R.drawable.ic_go_straight
            // autres types…
            else -> R.drawable.ic_navigation_arrow
        }
        holder.ivIcon.setImageResource(iconRes)
    }

    override fun getItemCount(): Int = steps.size
}