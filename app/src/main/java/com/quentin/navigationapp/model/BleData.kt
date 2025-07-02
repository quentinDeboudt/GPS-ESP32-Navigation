package com.quentin.navigationapp.model

sealed class BleData {
    data class Direction(val code: Int?) : BleData()
    data class DistanceBeforeDirection(val meters: Int) : BleData()
    data class VectorPath(val points: List<List<Double>>) : BleData()
    data class KilometersRemaining(val km: Int) : BleData()
    data class TimeRemaining(val time: Int) : BleData()
    data class CurrentPosition(val latitude: Double, val longitude: Double) : BleData()
    data class SpeedLimit(val speed: Int) : BleData()
}