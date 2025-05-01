package com.quentin.navigationapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.quentin.navigationapp.data.NavigationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import android.location.Address
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.quentin.navigationapp.ui.theme.StepAdapter

//Mini map Visuel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import android.graphics.Color
import android.preference.PreferenceManager
import com.quentin.navigationapp.model.DirectionsResponse


class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val navigationService = NavigationService()
    private lateinit var currentLatLng: LatLng
    private lateinit var currentAdress: List<Address>
    private lateinit var finishAddress: List<Address>

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("debug", "onCreate() appelé")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Instruction des chaque direction
        val rvSteps = findViewById<RecyclerView>(R.id.rvSteps)
        rvSteps.layoutManager = LinearLayoutManager(this)

        //Integration de la mini map visuel
        Configuration.getInstance()
            .load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))

        // Initialise fusedLocationClient en tout premier
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        getCurrentLocation()

        //gerer le bouton "Démarer la navigation"
        val etDestination: EditText = findViewById(R.id.etDestination)
        val startNavigationButton = findViewById<Button>(R.id.btnStartNavigation)

        startNavigationButton.setOnClickListener {
            val destinationAddress = etDestination.text.toString()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                if (destinationAddress.isNotEmpty()) {
                    getCoordinatesFromAddress(destinationAddress)
                } else {
                    Toast.makeText(this, "Veuillez entrer une destination", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Si la permission n'est pas accordée, demander à l'utilisateur
                Log.d("debug", "la permission pour obtenir la localisationest n'est pas accordée")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getCurrentLocation() {
        val tvPosition = findViewById<TextView>(R.id.et_current_position)
        val geocoder = Geocoder(this, Locale.getDefault())

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                currentLatLng = LatLng(location.latitude, location.longitude)

                currentAdress = geocoder.getFromLocation(
                    currentLatLng.latitude,
                    currentLatLng.longitude,
                    1 // nombre maximum de résultats
                )!!


                if (!currentAdress.isNullOrEmpty()) {
                    val currentPosition = currentAdress[0].getAddressLine(0)

                    // Mets à jour les textes
                    tvPosition.text = "🚩 $currentPosition"
                }

            } else {
                Toast.makeText(this, "Permission de localisation non accordée", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getCoordinatesFromAddress(address: String) {
        val geocoder = Geocoder(this)
        try {
            // Effectuer la géocodification
            finishAddress = geocoder.getFromLocationName(address, 1)!!

            if (finishAddress != null && finishAddress.isNotEmpty()) {
                val location = finishAddress[0]
                val destinationLatLng = LatLng(location.latitude, location.longitude)

                getDirections(currentLatLng, destinationLatLng)
            } else {
                Toast.makeText(this, "Adresse non trouvée", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Erreur de géocodification", Toast.LENGTH_SHORT).show()
        }
    }

    // Appel à l'API pour récupérer les directions
    private fun getDirections(currentLatLng: LatLng, destinationLatLng: LatLng) {

        displayAddress(destinationLatLng)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = navigationService.getDirections(currentLatLng, destinationLatLng)
                // 1. prends la première feature
                val feature = response.features.firstOrNull()

                // 2. récupère le premier segment
                val segment = feature?.properties?.segments?.firstOrNull()

                // 3. récupère les steps
                val steps = segment?.steps ?: emptyList()

                // 4. construis le texte
                val sb = StringBuilder()
                for ((i, step) in steps.withIndex()) {
                    sb.append("${i+1}. ${step.instruction}\n")
                }

                withContext(Dispatchers.Main) {
                    val rvSteps = findViewById<RecyclerView>(R.id.rvSteps)
                    val segment = response.features.first().properties.segments.first()
                    val steps = segment.steps
                    rvSteps.adapter = StepAdapter(steps)
                }

                displayMap(response)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("debug", "Erreur dans getDirections()", e)
                    Toast.makeText(this@MainActivity,
                        "Erreur lors de la récupération des directions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * displayMap - Display map with bleue navigation
     */
    private fun displayMap(response: DirectionsResponse){

        val mapView: MapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)

        val coords = response.features
            .firstOrNull()?.geometry?.coordinates
            ?.map { GeoPoint(it[1], it[0]) } ?: emptyList()

        mapView.controller.setCenter(coords.first())

        if (coords.isNotEmpty()) {
            // tracer la ligne
            val polyline = Polyline().apply {
                setPoints(coords)
                outlinePaint.color = Color.BLUE
                outlinePaint.strokeWidth = 8f
            }
            mapView.overlays.add(polyline)

            // marker départ
            Marker(mapView).apply {
                position = coords.first()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "$currentAdress"
            }.also { mapView.overlays.add(it) }

            // marker arrivée
            Marker(mapView).apply {
                position = coords.last()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "$finishAddress"
            }.also { mapView.overlays.add(it) }

            // ajuster vue
            val bb = BoundingBox.fromGeoPoints(coords)
            mapView.zoomToBoundingBox(bb, true)
        }
    }

    private fun displayAddress(destinationLatLng: LatLng) {
        val tvDestination = findViewById<TextView>(R.id.etDestination)

        val geocoder = Geocoder(this, Locale.getDefault())

        val destinationAddress: List<Address>? = geocoder.getFromLocation(
            destinationLatLng.latitude,
            destinationLatLng.longitude,
            1 // nombre maximum de résultats
        )

        if (!destinationAddress.isNullOrEmpty()) {
            val destination = destinationAddress[0].getAddressLine(0)
            // Mets à jour les textes
            tvDestination.text = "📍 $destination"
        }
    }
}
