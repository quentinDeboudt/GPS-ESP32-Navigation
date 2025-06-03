package com.quentin.navigationapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    // Instanciation “lazy” des fragments pour économiser les ressources
    private val page1Fragment: Fragment by lazy { HomeFragment() }
    private val page2Fragment: Fragment by lazy { MapFragment() }
    private val page3Fragment: Fragment by lazy { FavoritesFragment() }
    private val page4Fragment: Fragment by lazy { SettingFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialement, on affiche la Page 1
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, page1Fragment)
                .commit()
        }

        // Référence au BottomNavigationView
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        // Listener pour naviguer entre les fragments
        bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.navigation_page1 -> switchFragment(page1Fragment)
                R.id.navigation_page2 -> switchFragment(page2Fragment)
                R.id.navigation_page3 -> switchFragment(page3Fragment)
                R.id.navigation_page4 -> switchFragment(page4Fragment)
                else -> false
            }
        }
    }

    /**
     * Remplace le container par le fragment donné.
     * Retourne true si la transaction est effectuée.
     */
    private fun switchFragment(fragment: Fragment): Boolean {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
        return true
    }
}
