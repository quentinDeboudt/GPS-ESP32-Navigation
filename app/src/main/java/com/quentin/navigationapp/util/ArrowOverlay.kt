package com.quentin.navigationapp.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class ArrowOverlay(private val context: Context) : Overlay() {

    val arrowPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    val borderPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val centerX = mapView.width / 2f
        val centerY = mapView.height / 2f
        val arrowHeight = 80f
        val arrowWidth = 40f

        val path = Path().apply {
            moveTo(centerX, centerY - arrowHeight)
            lineTo(centerX - arrowWidth, centerY)
            lineTo(centerX + arrowWidth, centerY)
            close()
        }

        canvas.drawPath(path, arrowPaint)
        canvas.drawPath(path, borderPaint)
    }
}
