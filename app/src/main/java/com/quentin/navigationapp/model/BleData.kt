package com.quentin.navigationapp.model

sealed class BleData {
    data class Direction(val code: Int?) : BleData()
    data class DistanceBeforeDirection(val meters: String) : BleData()
    data class VectorPath(val points: List<List<Double>>) : BleData()
    data class KilometersRemaining(val km: String) : BleData()
    data class TimeRemaining(val timeText: String) : BleData()
    data class CurrentPosition(val latitude: Double, val longitude: Double) : BleData()
}