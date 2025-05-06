package com.quentin.navigationapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.location.Location
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.google.android.gms.maps.model.LatLng
import com.quentin.navigationapp.data.NavigationService
import com.quentin.navigationapp.model.DirectionsResponse
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Polyline
import kotlin.collections.first
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import kotlin.math.cos
import kotlin.math.sin
import android.graphics.Path

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var arrowMarker: Marker
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private val navigationService = NavigationService()
    private lateinit var currentPosition: GeoPoint
    private var routePoints: List<GeoPoint> = emptyList()
    private lateinit var tvInstruction: TextView
    private lateinit var arrowImageView: ImageView

    private lateinit var instructions: List<NavigationInstruction>
    private var lastPosition: Location? = null

    data class NavigationInstruction(
        val message: String,
        val location: GeoPoint,
        val arrow: Drawable? = null
    )

    // Dispatcher I/O pour les Flows
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // 1. Flow de positions GPS (1s intervalle) avec vérification explicite de permission
    private val locationFlow: Flow<Location> by lazy {
        callbackFlow {
            // Vérification de la permission avant de démarrer les mises à jour
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                close(SecurityException("Permission ACCESS_FINE_LOCATION non accordée"))
                return@callbackFlow
            }

            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)//interval = 1sec
                .setMinUpdateIntervalMillis(500L) //0,5 seconde
                .build()

            val cb = object : LocationCallback() {
                override fun onLocationResult(res: LocationResult) {
                    res.lastLocation?.let { trySend(it) }
                }
            }

            try {
                fusedLocationClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
                awaitClose { fusedLocationClient.removeLocationUpdates(cb) }
            } catch (e: SecurityException) {
                close(e)
                return@callbackFlow
            }

        }
            .flowOn(ioDispatcher)
            .conflate()
    }

    // 2. Flow d’orientation (compas)
    private val orientationFlow: Flow<Float> by lazy {
        callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.sensor.type == Sensor.TYPE_ORIENTATION) {
                        trySend(e.values[0])
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {}
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)?.also {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
            awaitClose { sensorManager.unregisterListener(listener) }
        }
            .flowOn(ioDispatcher)
            .conflate()
    }

    // 3. Flow de bearing vers le prochain point de la route
    private fun navigationBearingFlow(route: List<GeoPoint>): Flow<Float> =
        locationFlow
            .combine(orientationFlow) { loc, heading ->
                currentPosition = GeoPoint(loc.latitude, loc.longitude)
                val next = route.minByOrNull { point: GeoPoint ->
                    point.distanceToAsDouble(currentPosition)
                } ?: route.first()
                
                val bearingTo = currentPosition.bearingTo(next).toFloat()
                val angle = (bearingTo - heading) % 360f
                if (angle < 0) angle + 360f else angle
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OSMdroid configuration
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        //Configuration map:
        mapView = findViewById(R.id.map)
        tvInstruction = findViewById(R.id.tvInstruction)
        arrowImageView = findViewById(R.id.arrowImageView)
        val btnStartNavigation = findViewById<Button>(R.id.btnStartNavigation)
        val btnFinishNavigation = findViewById<Button>(R.id.btnFinishNavigation)


        // Masquer les bouton
        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.GONE
        tvInstruction.visibility = View.GONE

        //Récuperer la localisation:
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                configureMap(GeoPoint(lat, lon))
                currentPosition = GeoPoint(lat, lon)
            } else {
                Toast.makeText(this, "Impossible d’obtenir la localisation", Toast.LENGTH_SHORT).show()
            }
        }

        //Recherche de l'itineraire avec la localisation
        findViewById<Button>(R.id.searchNavigationButton).setOnClickListener {
            val etDestination: EditText = findViewById(R.id.etDestination)
            val destinationAddress = etDestination.text.toString()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (destinationAddress.isNotEmpty()) {
                    getCoordinatesFromAddress(destinationAddress)

                    // Afficher bouton "demarrer la navigation"
                    btnStartNavigation.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Veuillez entrer une destination", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Lancement de la navigation au clic
        requestPermissionsIfNeeded {
            findViewById<Button>(R.id.btnStartNavigation).setOnClickListener {
                lifecycleScope.launch {
                    lifecycleScope.launch {
                        locationFlow.collect {
                            updateArrowOverlay(it)

                            // afficher l'instruction
                            tvInstruction.visibility = View.VISIBLE
                            // Supprimer bouton "demarrer la navigation"
                            btnStartNavigation.visibility = View.GONE
                            // Afficher bouton "Arréter la navigation"
                            btnFinishNavigation.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        // Arret de la navigation au clic
        requestPermissionsIfNeeded {
            findViewById<Button>(R.id.btnFinishNavigation).setOnClickListener {
                lifecycleScope.launch {
                    lifecycleScope.launch {
                        locationFlow.collect {
                            updateArrowOverlay(it)

                            // afficher l'instruction
                            tvInstruction.visibility = View.VISIBLE
                            // Supprimer bouton "demarrer la navigation"
                            btnStartNavigation.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getCoordinatesFromAddress(address: String) {
        Log.d("GPS", "getCoordinatesFromAddress")
        val geocoder = Geocoder(this)
        try {

            // Effectuer la géocodification
            val finishAddress = geocoder.getFromLocationName(address, 1)!!

            if (finishAddress.isNotEmpty() == true) {
                val location = finishAddress[0]
                val destinationLatLng = LatLng(location?.latitude ?: 0.00 , location?.longitude ?: 0.00)
                val currentLocationLatLng = LatLng(currentPosition.latitude, currentPosition.longitude)

                displayAddress(finishAddress[0].getAddressLine(0).toString())
                getDirections(currentLocationLatLng, destinationLatLng)
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
        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Liste pour stocker les instructions et les positions
                val instructionsList = mutableListOf<NavigationInstruction>()

                val response = navigationService.getDirections(currentLatLng, destinationLatLng)
                // 1. prends la première feature
                val feature = response.features.firstOrNull()

                // 2. récupère le premier segment
                val segment = feature?.properties?.segments?.firstOrNull()

                // 3. récupère les steps
                val steps = segment?.steps ?: emptyList()


                // Accès aux features
                val features = response.features

                // Itération sur les features
                for (feature in features) {
                    val segments = feature.properties?.segments

                    segments?.forEach { segment ->
                        val steps = segment.steps // Récupère les étapes

                        // Récupère les coordonnées dans la feature.geometry
                        val coordinates = feature.geometry?.coordinates ?: emptyList()

                        // Itération sur les étapes pour récupérer l'instruction et les coordonnées
                        for (step in steps) {
                            val instruction = step.instruction // Récupère l'instruction de l'étape
                            val wayPoints = step.way_points // Récupère les way_points
                            val exitNumber = step.exit_number // Récupère le numéro de sortie

                            // Récupère les coordonnées à partir des indices dans way_points
                            val startCoord =
                                coordinates.getOrNull(wayPoints[0]) // Coordonnée de départ
                            val endCoord = coordinates.getOrNull(wayPoints[1]) // Coordonnée de fin

                            // Si les coordonnées sont valides, créer un GeoPoint et ajouter à la liste
                            if (startCoord != null && endCoord != null) {
                                val startGeoPoint =
                                    GeoPoint(startCoord[1], startCoord[0]) // [lat, lon]
                                val endGeoPoint = GeoPoint(endCoord[1], endCoord[0]) // [lat, lon]

                                val bearing = calculateBearing(startGeoPoint, endGeoPoint)

                                if(exitNumber != null){
                                   // Ajouter l'instruction et la coordonnée à la liste
                                   instructionsList.add(
                                       NavigationInstruction(
                                           instruction,
                                           startGeoPoint,
                                           getRoundaboutIconFromBearing(bearing)
                                       )
                                   )
                                   instructionsList.add(
                                       NavigationInstruction(
                                           instruction,
                                           endGeoPoint,
                                           getRoundaboutIconFromBearing(bearing)
                                       )
                                   )
                                }else {
                                   Log.d("GPS", "Pas de RondPoint: $instruction")
                                    instructionsList.add(
                                        NavigationInstruction(
                                            instruction,
                                            startGeoPoint,
                                            getArrowForInstruction(instruction)
                                        )
                                    )
                                    instructionsList.add(
                                        NavigationInstruction(
                                           instruction,
                                           endGeoPoint,
                                           getArrowForInstruction(instruction)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                instructions = instructionsList

                displayMap(response)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "Erreur lors de la récupération des directions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    // Définir des flèches en fonction de l'instruction
    fun getArrowForInstruction(instruction: String): Drawable? {
        return when {
            instruction.contains("right", ignoreCase = true) -> {
                ContextCompat.getDrawable(this, R.drawable.nav_right_2_bk)
            }
            instruction.contains("left", ignoreCase = true) -> {
                ContextCompat.getDrawable(this, R.drawable.nav_left_2_bk)

            }
            instruction.contains("straight", ignoreCase = true) -> {
                ContextCompat.getDrawable(this, R.drawable.ic_navigation_arrow)

            }
            else -> null // Pas de flèche si l'instruction est autre
        }
    }

    fun calculateBearing(start: GeoPoint, end: GeoPoint): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)

        val dLon = lon2 - lon1

        val x = Math.sin(dLon) * Math.cos(lat2)
        val y = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)

        val initialBearing = Math.atan2(x, y)

        // Convertir l'azimut de radians à degrés
        var degreeBearing = Math.toDegrees(initialBearing)

        // Normaliser l'angle entre 0 et 360 degrés
        if (degreeBearing < 0) {
            degreeBearing += 360
        }

        return -degreeBearing.toFloat()
    }

    fun getRoundaboutIconFromBearing(bearing: Float): Drawable? {

        return when (bearing) {
            in 337.5..360.0, in 0.0..22.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r1_bk)// N
            in 22.5..67.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r2_bk) // NE
            in 67.5..112.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r3_bk) // E
            in 112.5..157.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r4_bk) // SE
            in 157.5..202.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r5_bk) // S
            in 202.5..247.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r6_bk) // SW
            in 247.5..292.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r7_bk) // W
            in 292.5..337.4 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r8_bk) // NW
            else -> null
        }
    }








    private fun displayMap(response: DirectionsResponse) {

        routePoints = response.features
            .firstOrNull()?.geometry?.coordinates
            ?.map { GeoPoint(it[1], it[0]) } ?: emptyList()

        val roadOverlay = Polyline()
        roadOverlay.setPoints(routePoints)
        roadOverlay.color = Color.BLACK
        roadOverlay.width = 17f
        mapView.overlays.add(roadOverlay)
        mapView.overlays.add(arrowMarker)
        mapView.invalidate()
    }

    private fun displayAddress(destinationAddress: String) {

        val tvDestination = findViewById<TextView>(R.id.etDestination)

        if (destinationAddress.isNotEmpty()) {
            tvDestination.text = "📍 $destinationAddress"
        }
    }

    /** Configure la carte et la flèche **/
    private fun configureMap(location: GeoPoint) {

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.setZoom(18.0)

        // Crée la flèche
        arrowMarker = Marker(mapView).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_arrow) //ic_arrow
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }

        // Ajoute la flèche à la carte
        mapView.overlays.add(arrowMarker)

        arrowMarker.position = location
        mapView.controller.setCenter(location)
        mapView.invalidate()
    }

    /** Met à jour position + rotation de la flèche **/
    private fun updateArrowOverlay(position: Location) {
        val currentGeoPoint = GeoPoint(position.latitude, position.longitude)
        var bearing: Float = position.bearing

        // Met à jour la position de la flèche
        arrowMarker.position = currentGeoPoint
        arrowMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        // Met à jour l'orientation de la map & de la flèche
        mapView.mapOrientation = -bearing
        arrowMarker.rotation = 0f

        checkInstructionTrigger(GeoPoint(position.latitude, position.longitude))
        smoothCenterTo(GeoPoint(position.latitude, position.longitude))

        mapView.controller.setCenter(arrowMarker.position)
        mapView.controller.setZoom(18.0)

        lastPosition = position
        mapView.invalidate()
    }

    private var lastCenter: GeoPoint? = null

    private fun smoothCenterTo(newPosition: GeoPoint) {
        val old = lastCenter ?: newPosition
        val lat = old.latitude + (newPosition.latitude - old.latitude) * 0.1
        val lon = old.longitude + (newPosition.longitude - old.longitude) * 0.1
        val interpolated = GeoPoint(lat, lon)

        lastCenter = interpolated
        mapView.controller.setCenter(interpolated)
    }


    var currentInstructionIndex = 0

    private fun checkInstructionTrigger(currentLocation: GeoPoint) {
        if (currentInstructionIndex >= instructions.size) return

        val nextInstruction = instructions[currentInstructionIndex]
        val distance = currentLocation.distanceToAsDouble(nextInstruction.location)

        if (distance < 200.0) { // dans un rayon de 200m
            showInstruction(nextInstruction)
            currentInstructionIndex++
        }
    }

    private fun showInstruction(nextInstruction: NavigationInstruction) {
        // Mettre à jour l'ImageView avec l'image de la flèche
        arrowImageView.setImageDrawable(nextInstruction.arrow)

        tvInstruction.text = nextInstruction.message
        tvInstruction.visibility = View.VISIBLE
    }

    /** Vérifie et demande les permissions nécessaires **/
    private fun requestPermissionsIfNeeded(onGranted: () -> Unit) {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) onGranted()
        }
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            launcher.launch(perms)
        } else {
            onGranted()
        }
    }
}
