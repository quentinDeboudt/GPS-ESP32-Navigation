package com.quentin.navigationapp.ui.fragments.setting

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.RadioButton
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.fragment.app.DialogFragment
import com.quentin.navigationapp.R

class Esp32ConnectionDialogFragment : DialogFragment() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiManager: WifiManager
    private lateinit var deviceListView: ListView



    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
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



        btnConnect.setOnClickListener {
            if (radioBluetooth.isChecked) {
                afficherAppareilsBluetooth()
            } else if (radioWifi.isChecked) {
                afficherReseauxWifi()
            }
        }

        builder.setView(view)
        return builder.create()
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun afficherAppareilsBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth désactivé", Toast.LENGTH_SHORT).show()
            return
        }

        val appareils = bluetoothAdapter.bondedDevices
        val noms = appareils.map { "${it.name} (${it.address})" }

        if (noms.isEmpty()) {
            Toast.makeText(context, "Aucun appareil Bluetooth appairé trouvé.", Toast.LENGTH_SHORT).show()
            deviceListView.adapter = null
        } else {
            val noms = appareils.map { "${it.name ?: "Inconnu"} (${it.address})" }
            deviceListView.adapter =
                ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, noms)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun afficherReseauxWifi() {
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        val receiver = object : BroadcastReceiver() {
            @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            override fun onReceive(context: Context?, intent: Intent?) {

                val results = wifiManager.scanResults
                val noms = results.map { it.SSID }.filter { it.isNotBlank() }

                if (noms.isEmpty()) {
                    Toast.makeText(context, "Aucun appareil Bluetooth appairé trouvé.", Toast.LENGTH_SHORT).show()
                    deviceListView.adapter = null
                } else {
                    deviceListView.adapter =
                        ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, noms)
                    requireContext().unregisterReceiver(this)
                }
            }
        }

        requireContext().registerReceiver(receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        wifiManager.startScan()
    }
}