package com.quentin.navigationapp.ui.fragments.favoriteRoutes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.quentin.navigationapp.R

class FavoritesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // On “gonfle” simplement le layout
        return inflater.inflate(R.layout.favorite_road_page, container, false)
    }
}