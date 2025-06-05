package com.quentin.navigationapp.model

import android.net.Uri

data class Profile(
    val name: String,
    val type: String,
    val subType: VehicleSubType,
    val consumption: Double,
    val imageUri: Uri? = null
)
