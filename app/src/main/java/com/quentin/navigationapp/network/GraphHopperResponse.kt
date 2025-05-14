package com.quentin.navigationapp.network

data class GraphHopperResponse(
    val paths: List<Path>
)

data class Path(
    val distance: Double,
    val time: Long,
    val points: Points,
    val instructions: List<Instruction>
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
