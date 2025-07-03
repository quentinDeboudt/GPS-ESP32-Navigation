package com.quentin.navigationapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.annotation.RequiresPermission
import com.quentin.navigationapp.model.BleData
import java.util.UUID

object BluetoothManager {
    private var gatt: BluetoothGatt? = null
    private var connected = false

    /** Appelé juste après le connectGatt() réussi */
    fun setGatt(btGatt: BluetoothGatt) {
        gatt = btGatt
        connected = true
    }

    fun isConnected(): Boolean = connected

    @SuppressLint("MissingPermission")
    fun sendData(data: BleData) {

        val characteristic = getCharacteristic()

        val message: String = when (data) {
            is BleData.Direction -> "DIR:${data.code}"
            is BleData.DistanceBeforeDirection -> "DIST:${data.meters}"
            is BleData.VectorPath -> "PATH:${data.points}"
            is BleData.KilometersRemaining -> "KM:${data.km}"
            is BleData.TimeRemaining -> "TIME:${data.time}"
            is BleData.CurrentPosition -> "POSITION:${data}"
            is BleData.SpeedLimit -> "SPEEDLIMIT:${data.speed}"
        }


        Log.w("debugSendData", "ESP32: $message")

        if (characteristic != null && connected) {
           characteristic.value = message.toByteArray(Charsets.UTF_8)
           gatt?.writeCharacteristic(characteristic)
        } else {
            Log.e("debugSendData", "Erreur : pas connecté ou caractéristique manquante")
        }
    }

    fun getCharacteristic(): BluetoothGattCharacteristic? {
        //UUID par défaut
        val serviceUuid = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val charUuid    = UUID.fromString("0000dcba-0000-1000-8000-00805f9b34fb")

        return gatt
            ?.getService(serviceUuid)
            ?.getCharacteristic(charUuid)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        gatt?.close()
        gatt = null
    }
}
