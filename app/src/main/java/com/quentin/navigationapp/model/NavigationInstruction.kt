package com.quentin.navigationapp.model

import android.graphics.drawable.Drawable
import org.osmdroid.util.GeoPoint

data class NavigationInstruction(
    val message: String,
    val location: GeoPoint,
    val arrow: Int
)