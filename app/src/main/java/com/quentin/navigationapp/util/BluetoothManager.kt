package com.quentin.navigationapp.util

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException

object BluetoothManager {
    private var socket: BluetoothSocket? = null

    fun setSocket(btSocket: BluetoothSocket) {
        socket = btSocket
    }

    fun isConnected(): Boolean {
        return socket?.isConnected == true
    }

    fun sendData(message: String) {
        socket?.let { sock ->
            Thread {
                try {
                    val outputStream = sock.outputStream
                    outputStream.write(message.toByteArray())
                    outputStream.flush()
                    Log.d("BluetoothManager", "Donnée envoyée : $message")
                } catch (e: IOException) {
                    Log.e("BluetoothManager", "Erreur d’envoi : ${e.message}")
                }
            }.start()
        } ?: run {
            Log.e("BluetoothManager", "Socket Bluetooth non initialisé")
        }
    }
}
