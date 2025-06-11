package com.quentin.navigationapp.ui.fragments.setting

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.fragment.app.DialogFragment
import com.quentin.navigationapp.R
import com.quentin.navigationapp.util.PermissionUtils
import java.util.UUID


class Esp32ConnectionDialogFragment : DialogFragment() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiManager: WifiManager
    private lateinit var deviceListView: ListView
    private var currentReceiver: BroadcastReceiver? = null

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_device_connect, null)

        val radioBluetooth = view.findViewById<RadioButton>(R.id.radioBluetooth)
        val radioWifi = view.findViewById<RadioButton>(R.id.radioWifi)
        val btnConnect = view.findViewById<Button>(R.id.btnConnect)

        deviceListView = view.findViewById(R.id.device_list)
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        btnConnect.setOnClickListener{
            when {
                radioBluetooth.isChecked -> {
                    if (!PermissionUtils.hasPermissions(requireContext(), PermissionUtils.bluetoothPermissions)) {
                        PermissionUtils.requestPermissions(
                            requireActivity(),
                            PermissionUtils.bluetoothPermissions,
                            PermissionUtils.REQUEST_CODE_BLUETOOTH
                        )
                    } else {
                        displayBluetooth()
                    }
                }
                radioWifi.isChecked -> {
                    if (!PermissionUtils.hasPermissions(requireContext(), PermissionUtils.wifiPermissions)) {
                        PermissionUtils.requestPermissions(
                            requireActivity(),
                            PermissionUtils.wifiPermissions,
                            PermissionUtils.REQUEST_CODE_WIFI
                        )
                    } else {
                        displayWifi()
                    }
                }
            }
        }

        deviceListView.setOnItemClickListener { _, _, position, _ ->

            if (radioBluetooth.isChecked) {

                val Device2 = deviceListView.getItemAtPosition(position) as String
                val regex = "\\(([^)]+)\\)".toRegex()
                val uuid =regex.find(Device2)?.groupValues?.get(1)

                val device = bluetoothAdapter.bondedDevices.firstOrNull {
                    it.address == uuid
                }

                Thread {
                    try {
                        val socket = device?.createRfcommSocketToServiceRecord( UUID.fromString(uuid))
                        socket?.connect()

                        //bluetoothAdapter.cancelDiscovery()

                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Connecté à ${socket}", Toast.LENGTH_SHORT).show()
                        }

                        socket?.close()
                    } catch (e: Exception) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Échec de connexion : ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            /*
            if (radioBluetooth.isChecked) {

                Toast.makeText(requireContext(), "Connexion à ${device.name}...", Toast.LENGTH_SHORT).show()

                Thread {
                    try {
                        val uuid = device.uuids?.firstOrNull()?.uuid
                            ?: UUID.fromString("00001101-0000-1000-8000-00805f9b34fb") // UUID SPP par défaut
                        val socket = device.createRfcommSocketToServiceRecord(uuid)

                        //bluetoothAdapter.cancelDiscovery()
                        socket.connect()

                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Connecté à ${device.name}", Toast.LENGTH_SHORT).show()
                        }

                        socket.close()
                    } catch (e: Exception) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Échec de connexion : ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            } else if (radioWifi.isChecked) {
                val selectedSsid = deviceListView.getItemAtPosition(position) as String
                Toast.makeText(requireContext(), "Tentative de connexion à $selectedSsid (simulation)", Toast.LENGTH_SHORT).show()
                // Tu peux implémenter une vraie connexion Wi-Fi ici si besoin.
            }
             */
        }



        builder.setView(view)
        return builder.create()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun displayBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth désactivé", Toast.LENGTH_SHORT).show()
            return
        }

        val appareils = bluetoothAdapter.bondedDevices
        val noms = appareils.map { "${it.name ?: "Inconnu"} (${it.address})" }

        deviceListView.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, noms)
    }

    private fun displayWifi() {
        if (!PermissionUtils.hasPermissions(requireContext(), PermissionUtils.wifiPermissions)) {
            Toast.makeText(requireContext(), "Permissions Wi-Fi manquantes", Toast.LENGTH_SHORT).show()
            PermissionUtils.requestPermissions(requireActivity(), PermissionUtils.wifiPermissions, PermissionUtils.REQUEST_CODE_WIFI)
            return
        }

        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        currentReceiver?.let {
            try {
                requireContext().unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered or already unregistered
            }
        }

        currentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!PermissionUtils.hasPermissions(requireContext(), PermissionUtils.wifiPermissions)) {
                    Toast.makeText(requireContext(), "Permissions Wi-Fi manquantes", Toast.LENGTH_SHORT).show()
                    return
                }

                val results = try {
                    wifiManager.scanResults
                } catch (e: SecurityException) {
                    Toast.makeText(requireContext(), "Permission refusée pour obtenir les réseaux", Toast.LENGTH_SHORT).show()
                    return
                }

                val noms = results.map { it.SSID }.filter { it.isNotBlank() }
                deviceListView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, noms)

                requireContext().unregisterReceiver(this)
                currentReceiver = null
            }
        }

        requireContext().registerReceiver(
            currentReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        try {
            wifiManager.startScan()
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "Permission refusée pour lancer le scan", Toast.LENGTH_SHORT).show()
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PermissionUtils.REQUEST_CODE_BLUETOOTH -> {
                if (PermissionUtils.permissionsGranted(grantResults)) {
                    displayBluetooth()
                } else {
                    Toast.makeText(requireContext(), "Permission Bluetooth refusée", Toast.LENGTH_SHORT).show()
                }
            }
            PermissionUtils.REQUEST_CODE_WIFI -> {
                if (PermissionUtils.permissionsGranted(grantResults)) {
                    displayWifi()
                } else {
                    Toast.makeText(requireContext(), "Permission Wi-Fi refusée", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
