package com.quentin.navigationapp

import android.Manifest
import android.annotation.SuppressLint
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

//Mini map
import android.graphics.*
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.quentin.navigationapp.model.DirectionsResponse
import org.osmdroid.util.GeoPoint
import androidx.core.graphics.withRotation
import com.quentin.navigationapp.util.ArrowOverlay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay


class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val navigationService = NavigationService()
    private lateinit var currentLatLng: LatLng
    private lateinit var currentAdress: List<Address>
    private lateinit var finishAddress: List<Address>
    private var currentLocation: GeoPoint? = null
    private var currentBearing: Float = 0f

    private lateinit var locationCallback: LocationCallback



    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("debug", "onCreate() appelé")
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, getSharedPreferences("prefs", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        // Instruction direction
        val rvSteps = findViewById<RecyclerView>(R.id.rvSteps)
        rvSteps.layoutManager = LinearLayoutManager(this)


        // Initialise fusedLocationClient en tout premier
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startLocationUpdates()

        getAddressForCurrentLocation()

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

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)//interval = 1sec
            .setMinUpdateIntervalMillis(500L) //0,5 seconde
            .build()

        //locationCallback est une variable globale pour eviter la perte de donnée (onPause() / onStop())
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                val location = p0.lastLocation ?: return
                val bearing = location.bearing
                updateCurrentLocation(location.latitude, location.longitude, bearing)
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    fun updateCurrentLocation(lat: Double, lon: Double, bearing: Float) {
        this.currentLocation = GeoPoint(lat, lon)
        this.currentBearing = bearing

        //val mapContainer = findViewById<FrameLayout>(R.id.map)
        //val customView = mapContainer.getChildAt(0)

        //customView?.invalidate() // redessine la vue
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getAddressForCurrentLocation() {
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
                    initMap(currentPosition)
                }else{
                    Toast.makeText(this, "localisation désactivé", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Permission de localisation non accordée", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun initMap(currentPosition: String) {

        //Initialise Map
        val map = findViewById<MapView>(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)

        map.setMultiTouchControls(true)
        val mapController = map.controller

        map.controller?.let { controller ->
            controller.setZoom(15.0)
            val startPoint = GeoPoint(currentLatLng.latitude, currentLatLng.longitude)
            controller.setCenter(startPoint)

            // Création du marqueur (point bleu par défaut)
            val marker = Marker(map)
            marker.position = startPoint
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            marker.title = "$currentPosition"
            map.overlays.add(marker)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getCoordinatesFromAddress(address: String) {
        val geocoder = Geocoder(this)
        try {
            // Effectuer la géocodification
            finishAddress = geocoder.getFromLocationName(address, 1)!!

            if (finishAddress.isNotEmpty()) {
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
     * displayMap - Affiche la carte avec le tracé de navigation.
     */
    private fun displayMap(response: DirectionsResponse) {

        val mapView = findViewById<MapView>(R.id.map)
        mapView.overlays.clear()
        mapView.setMultiTouchControls(false)
        mapView.isClickable = false
        mapView.setOnTouchListener { _, _ -> true }

        val coords: List<GeoPoint> = response.features
            .firstOrNull()?.geometry?.coordinates
            ?.map { GeoPoint(it[1], it[0]) } ?: emptyList()

        // Si le tracé est valide, on l'ajoute à la carte
        if (coords.size >= 2) {
            val polyline = Polyline(mapView)
            polyline.setPoints(coords)  // Définir les coordonnées du tracé
            polyline.outlinePaint.color = Color.BLUE  // Couleur du tracé
            polyline.outlinePaint.strokeWidth = 15f  // Largeur du tracé
            mapView.overlayManager.add(polyline)  // Ajouter le tracé sur la carte
        }

        // ➕ Ajout de la flèche centrée
        val arrowOverlay = ArrowOverlay(this)
        mapView.overlays.add(arrowOverlay)

        // ➕ Centrer la carte sur le début du tracé
        if (coords.isNotEmpty()) {
            mapView.controller.setCenter(coords.first())
        }

        mapView.invalidate()
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.5)
        mapView.controller.setCenter(coords.first())

        val rotationGestureOverlay = RotationGestureOverlay(mapView)
        rotationGestureOverlay.isEnabled = true
        mapView.overlays.add(rotationGestureOverlay)

        mapView.mapOrientation = currentBearing
    }

    /**
     * displayMap - Deplay map with path navigation.
     */
    private fun displayMap2(response: DirectionsResponse) {
        val coords: List<GeoPoint> = response.features
            .firstOrNull()?.geometry?.coordinates
            ?.map { GeoPoint(it[1], it[0]) } ?: emptyList()

        val customMapView = object : View(this) {
            val pathPaint = Paint().apply {
                color = Color.BLUE
                strokeWidth = 15f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val bgPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawPaint(bgPaint)

                if (coords.size < 2) return

                // Centre de l’écran (triangle toujours ici)
                val centerX = width / 2f
                val centerY = height / 2f

                // On projette toutes les coordonnées en relatif à la position actuelle
                val projected = projectCoordsToScreen(coords, width, height)

                // Création du chemin
                val path = Path().apply {
                    moveTo(projected[0].x, projected[0].y)
                    for (i in 1 until projected.size) {
                        lineTo(projected[i].x, projected[i].y)
                    }
                }

                // Tourne la carte sous le triangle
                canvas.withRotation(-currentBearing, centerX, centerY) {
                    canvas.drawPath(path, pathPaint)
                }

                // Dessine la flèche (triangle) au centre
                drawArrow(canvas, centerX, centerY)
            }

            fun drawArrow(canvas: Canvas, centerX: Float, centerY: Float) {
                val arrowPaint = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                val borderPaint = Paint().apply {
                    color = Color.BLACK
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }

                val path = Path()
                val arrowHeight = 80f
                val arrowWidth = 40f

                path.moveTo(centerX, centerY - arrowHeight)
                path.lineTo(centerX - arrowWidth, centerY)
                path.lineTo(centerX + arrowWidth, centerY)
                path.close()

                canvas.drawPath(path, arrowPaint)
                canvas.drawPath(path, borderPaint)
            }

            fun projectCoordsToScreen(
                geoPoints: List<GeoPoint>,
                width: Int,
                height: Int
            ): List<PointF> {
                val centerLat = currentLocation?.latitude ?: geoPoints.map { it.latitude }.average()
                val centerLon = currentLocation?.longitude ?: geoPoints.map { it.longitude }.average()
                val scale = 100000.0
                val centerX = width / 2f
                val centerY = height / 2f

                return geoPoints.map {
                    val dx = (it.longitude - centerLon) * scale
                    val dy = (it.latitude - centerLat) * -scale
                    PointF(centerX + dx.toFloat(), centerY + dy.toFloat())
                }
            }
        }

        val mapContainer = findViewById<FrameLayout>(R.id.map)
        mapContainer.removeAllViews()
        mapContainer.addView(customMapView)
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
