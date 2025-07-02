package com.quentin.navigationapp.network

import android.R

data class GraphHopperResponse(
    val paths: List<Path>
)
data class SpeedSegment(val fromIndex: Int, val toIndex: Int, val speed: Int?)

data class Path(
    val distance: Double,
    val time: Long,
    val points: Points,
    val instructions: List<Instruction>,
    val details: Map<String, List<List<Any>>>
)

data class Points(
    val type: String,
    val coordinates: List<List<Double>>
)

data class Instruction(
    val distance: Double,
    val time: Long,
    val text: String,
    val street_name: String,
    val exit_number: Int? = null,
    val sign: Int? = null,
    val turn_angle: Double? = null,
    val interval: List<Int>,
    val exited: Boolean? = null
)
