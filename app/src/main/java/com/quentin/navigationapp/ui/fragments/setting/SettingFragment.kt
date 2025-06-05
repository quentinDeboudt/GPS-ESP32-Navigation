package com.quentin.navigationapp.ui.fragments.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.quentin.navigationapp.R

class SettingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // On “gonfle” simplement le layout
        return inflater.inflate(R.layout.setting_page, container, false)
    }
}