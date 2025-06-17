package com.quentin.navigationapp.ui.fragments.setting

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.quentin.navigationapp.R
import com.quentin.navigationapp.util.BluetoothManager

class Esp32ConnectionDialogFragment : DialogFragment() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        mgr.adapter
    }
    private var scanner: BluetoothLeScanner? = null
    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnScan: Button
    private var pairingReceiver: BroadcastReceiver? = null
    private lateinit var adapter: DevicesAdapter
    private var targetDevice: BluetoothDevice? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_device_connect, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_ble_devices)
        btnScan = view.findViewById(R.id.btnConnectSend)

        adapter = DevicesAdapter(devices) { device ->
            stopScan()
            targetDevice = device
            registerPairingReceiver()
            device.createBond()
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnScan.setOnClickListener {
            devices.clear()
            adapter.notifyDataSetChanged()
            if (checkPermissions()) startScan() else requestPermissions()
        }

        if (checkPermissions()) startScan() else requestPermissions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopScan()
        pairingReceiver?.let { requireContext().unregisterReceiver(it) }
    }

    private fun registerPairingReceiver() {
        if (pairingReceiver != null) return
        pairingReceiver = object : BroadcastReceiver() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        if (device == targetDevice) {
                            when (bondState) {
                                BluetoothDevice.BOND_BONDED -> {
                                    Log.d("BLEBond", "Bonded with ${device?.name}")
                                    connectToDevice(device)
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    Toast.makeText(requireContext(), "Bond failed or cancelled", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        requireContext().registerReceiver(pairingReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        bluetoothAdapter?.bluetoothLeScanner?.let {
            scanner = it
            it.startScan(scanCallback)
            Toast.makeText(requireContext(), "Scan BLE démarré", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!device.name.isNullOrBlank() && !devices.contains(device)) {
                requireActivity().runOnUiThread {
                    devices.add(device)
                    adapter.notifyItemInserted(devices.size - 1)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice?) {
        device?.connectGatt(requireContext(), false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: android.bluetooth.BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                requireActivity().runOnUiThread {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Toast.makeText(requireContext(), "Connection etablie avec:  ${gatt.device.name}", Toast.LENGTH_SHORT).show()

                        BluetoothManager.setGatt(gatt)
                        gatt.discoverServices()  // pour charger la liste des services
                        dismiss()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Toast.makeText(requireContext(), "Déconnecté", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkPermissions(): Boolean {
        val perms = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        return perms.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestPermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            Toast.makeText(requireContext(), "Permissions requises manquantes", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}

// Adapter pour la liste des appareils BLE
class DevicesAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DevicesAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView = itemView.findViewById<android.widget.TextView>(android.R.id.text1)
        fun bind(device: BluetoothDevice) {
            nameView.text = device.name ?: "Unknown"
            itemView.isClickable = true
            itemView.setOnClickListener {
                onClick(device)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size
}
