package com.quentin.navigationapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import androidx.annotation.RequiresPermission
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
    fun sendData(text: String) {
        // Tes UUID par défaut
        val serviceUuid = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val charUuid    = UUID.fromString("0000dcba-0000-1000-8000-00805f9b34fb")

        val characteristic = gatt
            ?.getService(serviceUuid)
            ?.getCharacteristic(charUuid)

        if (characteristic != null && connected) {
            characteristic.value = text.toByteArray(Charsets.UTF_8)
            gatt?.writeCharacteristic(characteristic)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        gatt?.close()
        gatt = null
    }
}
