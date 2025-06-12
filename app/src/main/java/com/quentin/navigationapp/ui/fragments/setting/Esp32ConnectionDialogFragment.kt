package com.quentin.navigationapp.ui.fragments.setting

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.*
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.fragment.app.DialogFragment
import com.quentin.navigationapp.R
import com.quentin.navigationapp.util.BluetoothManager
import java.io.IOException
import java.util.UUID


class Esp32ConnectionDialogFragment : DialogFragment() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null

    private lateinit var btnConnectSend: Button

    private val sppUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // UUID SPP classique

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_device_connect, container, false)

        btnConnectSend = view.findViewById(R.id.btnConnectSend)
        btnConnectSend.setOnClickListener {
            if (bluetoothSocket?.isConnected == true) {
                // Si déjà connecté, envoie un message
                BluetoothManager.sendData("Coordonnées: 48.8566, 2.3522\n")
            } else {
                connectToFirstPairedDevice()
            }
        }

        return view
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToFirstPairedDevice() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Aucun appareil jumelé trouvé", Toast.LENGTH_SHORT).show()
            return
        }

        // Par exemple on prend le premier appareil jumelé (tu peux filtrer par nom)
        connectedDevice = pairedDevices.first()
        Log.d("BT", "Appareil sélectionné : ${connectedDevice?.name} - ${connectedDevice?.address}")

        Thread {
            try {
                bluetoothSocket = connectedDevice?.createRfcommSocketToServiceRecord(sppUUID)
                bluetoothAdapter?.cancelDiscovery() // Stop la recherche Bluetooth, ça peut gêner la connexion
                bluetoothSocket?.connect()

                BluetoothManager.setSocket(bluetoothSocket!!)
                Log.d("BT", "Connecté à ${connectedDevice?.name}")

                // Après connexion, envoie un message test (optionnel)
                BluetoothManager.sendData("Connexion réussie !\n")

                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Connecté à ${connectedDevice?.name}", Toast.LENGTH_SHORT).show()
                }

            } catch (e: IOException) {
                Log.e("BT", "Erreur de connexion : ${e.message}")
                try {
                    bluetoothSocket?.close()
                } catch (closeException: IOException) {
                    Log.e("BT", "Erreur fermeture socket : ${closeException.message}")
                }
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Erreur de connexion : ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
