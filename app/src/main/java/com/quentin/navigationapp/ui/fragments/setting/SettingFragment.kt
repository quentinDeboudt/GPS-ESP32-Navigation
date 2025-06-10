package com.quentin.navigationapp.ui.fragments.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.quentin.navigationapp.R

class SettingFragment : Fragment() {
    private lateinit var btnOpenModal: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.setting_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        btnOpenModal = view.findViewById(R.id.btnOpenModal)

        // open dialog connection wifi/bluetooth
        btnOpenModal.setOnClickListener {
            val dialog = Esp32ConnectionDialogFragment()
            dialog.show(parentFragmentManager, "DeviceConnectDialog")
        }
    }



}