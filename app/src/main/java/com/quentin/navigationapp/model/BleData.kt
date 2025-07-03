package com.quentin.navigationapp.model

import org.json.JSONArray

sealed class BleData {
    data class Direction(val code: Int?) : BleData()
    data class DistanceBeforeDirection(val meters: String) : BleData()
    data class VectorPath(val points: JSONArray) : BleData()
    data class KilometersRemaining(val km: Int) : BleData()
    data class TimeRemaining(val time: Int) : BleData()
    data class CurrentPosition(val latitude: Double, val longitude: Double) : BleData()
    data class SpeedLimit(val speed: Int) : BleData()
}