package com.quentin.navigationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.model.LatLng
import com.quentin.navigationapp.data.NavigationService
import com.quentin.navigationapp.model.DirectionsResponse
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.collections.first


//@SuppressLint("MissingPermission")
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

    data class NavigationInstruction(
        val message: String,
        val location: GeoPoint,
        val arrow: Drawable? = null
    )

    // Dispatcher I/O pour les Flows
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // 1. Flow de positions GPS (1s intervalle) avec vérification explicite de permission
    private val locationFlow: Flow<Location> by lazy {
        Log.d("GPS", "locationFlow")
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
                Log.e("GPS", "Permission refusée lors de requestLocationUpdates: ${e.message}")
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
            Log.d("GPS", "orientationFlow")

            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.sensor.type == Sensor.TYPE_ORIENTATION) {
                        Log.d("GPS", "Orientation reçue: ${e.values[0]}")
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
                Log.d("GPS", "navigationBearingFlow")
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
        Log.d("GPS", "onCreate")
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

        //Récuperer la localisation:
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                configureMap(GeoPoint(lat, lon))
                currentPosition = GeoPoint(lat, lon)
                Log.d("GPS", "localisation récuperer: $currentPosition")
            } else {
                Log.d("GPS", "Impossible d’obtenir la localisation")
            }
        }

        //Recherche de l'itineraire avec la localisation
        findViewById<Button>(R.id.searchNavigationButton).setOnClickListener {
            val etDestination: EditText = findViewById(R.id.etDestination)
            val destinationAddress = etDestination.text.toString()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (destinationAddress.isNotEmpty()) {
                    getCoordinatesFromAddress(destinationAddress)
                } else {
                    Toast.makeText(this, "Veuillez entrer une destination", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Lancement de la navigation au clic
        requestPermissionsIfNeeded {
            findViewById<Button>(R.id.btnStartNavigation).setOnClickListener {
                Log.d("GPS", "Lancement de la navigation: ")
                lifecycleScope.launch {
                    Log.d("GPS", "🌀 Début de la collecte du bearingFlow")
                    lifecycleScope.launch {
                        locationFlow.collect {
                            updateArrowOverlay(it)
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
            Log.d("GPS", "finish Address: $finishAddress")


            if (finishAddress.isNotEmpty() == true) {
                val location = finishAddress[0]
                val destinationLatLng = LatLng(location?.latitude ?: 0.00 , location?.longitude ?: 0.00)

                Log.d("GPS", "Current Address: ${currentPosition.latitude}, ${currentPosition.longitude}")
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

                            // Récupère les coordonnées à partir des indices dans way_points
                            val startCoord =
                                coordinates.getOrNull(wayPoints[0]) // Coordonnée de départ
                            val endCoord = coordinates.getOrNull(wayPoints[1]) // Coordonnée de fin

                            // Si les coordonnées sont valides, créer un GeoPoint et ajouter à la liste
                            if (startCoord != null && endCoord != null) {
                                val startGeoPoint =
                                    GeoPoint(startCoord[1], startCoord[0]) // [lat, lon]
                                val endGeoPoint = GeoPoint(endCoord[1], endCoord[0]) // [lat, lon]

                                // Ajouter l'instruction et la coordonnée à la liste
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
    }

    // Définir des flèches en fonction de l'instruction
    fun getArrowForInstruction(instruction: String): Drawable? {
        return when {
            instruction.contains("right", ignoreCase = true) -> {
                resources.getDrawable(R.drawable.ic_turn_right, theme)  // flèche droite
            }
            instruction.contains("left", ignoreCase = true) -> {
                resources.getDrawable(R.drawable.ic_turn_left, theme)   // flèche gauche
            }
            instruction.contains("straight", ignoreCase = true) -> {
                resources.getDrawable(R.drawable.ic_navigation_arrow, theme) // flèche tout droit
            }
            else -> null // Pas de flèche si l'instruction est autre
        }
    }

    private fun displayMap(response: DirectionsResponse) {

        routePoints = response.features
            .firstOrNull()?.geometry?.coordinates
            ?.map { GeoPoint(it[1], it[0]) } ?: emptyList()

        val roadOverlay = Polyline()
        roadOverlay.setPoints(routePoints)
        roadOverlay.color = Color.BLUE
        roadOverlay.width = 10f
        mapView.overlays.add(roadOverlay)
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
        arrowMarker = Marker(mapView).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_arrow)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        arrowMarker.position = location
        arrowMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mapView.controller.setCenter(location)
        mapView.overlays.add(arrowMarker)
        mapView.invalidate()
    }

    /** Met à jour position + rotation de la flèche **/
    private fun updateArrowOverlay(position: Location) {

        arrowMarker.position = GeoPoint(position.latitude, position.longitude)
        arrowMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        mapView.mapOrientation = -position.bearing // Rotation inverse pour que la flèche reste "vers le haut"
        arrowMarker.rotation = 0f

        checkInstructionTrigger(GeoPoint(position.latitude, position.longitude))
        smoothCenterTo(GeoPoint(position.latitude, position.longitude))

        mapView.controller.setCenter(arrowMarker.position)
        mapView.controller.setZoom(20.0)
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

        if (distance < 100.0) { // dans un rayon de 100m
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


/*package com.quentin.navigationapp

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
*/