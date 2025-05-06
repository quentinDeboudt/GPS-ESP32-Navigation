package com.quentin.navigationapp.ui.theme

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.quentin.navigationapp.R

class InstructionFragment : Fragment(R.layout.fragment_instruction) {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Gérer les instructions de navigation et mettre à jour l'UI ici
        return inflater.inflate(R.layout.fragment_instruction, container, false)
    }
}


