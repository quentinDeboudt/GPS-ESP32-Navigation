package com.quentin.navigationapp

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.google.android.gms.maps.model.LatLng
import com.quentin.navigationapp.data.NavigationService
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import org.osmdroid.views.overlay.Polyline
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Spinner
import com.quentin.navigationapp.network.Path
import kotlinx.coroutines.Job
import kotlin.math.round

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
    private lateinit var tvDestination: TextView
    private lateinit var layoutInstruction: LinearLayout
    private lateinit var layoutControl: LinearLayout
    private lateinit var navigationTime: TextView
    private lateinit var navigationDistance: TextView
    private var arrowIcon: Int = R.drawable.ic_arrow_motorbike

    private lateinit var instructions: List<NavigationInstruction>
    private var lastPosition: Location? = null
    private var currentInstructionIndex = 0
    private var lastCenter: GeoPoint? = null

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

    /**
     * onCreate
     * @info : works when the application is launched
     * @param savedInstanceState : Bundle
     * @call: configureMap
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSM droid configuration
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mapView = findViewById(R.id.map)
        tvInstruction = findViewById(R.id.tvInstruction)
        arrowImageView = findViewById(R.id.arrowImageView)
        tvDestination = findViewById<TextView>(R.id.etDestination)
        layoutInstruction = findViewById<LinearLayout>(R.id.layout_instruction)
        layoutControl = findViewById<LinearLayout>(R.id.layout_control)
        navigationTime = findViewById<TextView>(R.id.navigation_time)
        navigationDistance = findViewById<TextView>(R.id.navigation_distance)
        val btnStartNavigation = findViewById<Button>(R.id.btnStartNavigation)
        val layoutNavigationInput = findViewById<LinearLayout>(R.id.layout_navigation_Input)
        val btnFinishNavigation = findViewById<Button>(R.id.btnFinishNavigation)
        val layoutFilter = findViewById<LinearLayout>(R.id.layout_filter)

        // Hide buttons
        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.GONE
        layoutInstruction.visibility = View.GONE
        layoutControl.visibility = View.GONE

        // Retrieve location:
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

        // Spinner config (bike? motorcycle? ...)
        val spinner = findViewById<Spinner>(R.id.transport_spinner)
        val icons = listOf(R.drawable.ic_motorbike, R.drawable.ic_bike, R.drawable.ic_car)

        val adapter = object : ArrayAdapter<Int>(this, R.layout.item_icon_only_spinner, icons) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val imageView = LayoutInflater.from(context).inflate(R.layout.item_icon_only_spinner, parent, false) as ImageView
                imageView.setImageResource(getItem(position)!!)
                return imageView
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val imageView = LayoutInflater.from(context).inflate(R.layout.item_icon_only_spinner, parent, false) as ImageView
                imageView.setImageResource(getItem(position)!!)
                return imageView
            }
        }
        spinner.adapter = adapter


        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedIcon = icons[position]
                var newPosition: Int = -1

                when (selectedIcon) {
                    R.drawable.ic_motorbike -> {
                        arrowIcon = R.drawable.ic_arrow_motorbike
                        newPosition = 0
                    }
                    R.drawable.ic_bike -> {
                        arrowIcon = R.drawable.ic_bike_arrow
                        newPosition = 1

                    }
                    R.drawable.ic_car -> {
                        arrowIcon = R.drawable.ic_car_arrow
                        newPosition = 2
                    }

                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Optionnel
            }
        }

        //Recherche de l'itineraire avec la localisation
        findViewById<Button>(R.id.searchNavigationButton).setOnClickListener {
            val destinationAddress = tvDestination.text.toString()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (destinationAddress.isNotEmpty()) {
                    getCoordinatesFromAddress(destinationAddress)

                    // Afficher bouton "demarrer la navigation"
                    layoutControl.visibility = View.VISIBLE
                    btnStartNavigation.visibility = View.VISIBLE
                    layoutFilter.visibility = View.GONE

                    val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
                    params.topMargin = 70 * resources.displayMetrics.density.toInt() // 50dp
                    mapView.layoutParams = params

                } else {
                    Toast.makeText(this, "Veuillez entrer une destination", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Lancement de la navigation au clic
        requestPermissionsIfNeeded {
            findViewById<Button>(R.id.btnStartNavigation).setOnClickListener {
                lifecycleScope.launch {
                    locationFlow.collect {
                        updateArrowOverlay(it)

                        val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
                        params.topMargin = 0
                        mapView.layoutParams = params

                        val tvInstructionParams = layoutInstruction.layoutParams as ViewGroup.MarginLayoutParams
                        tvInstructionParams.topMargin = 10 * resources.displayMetrics.density.toInt()
                        layoutInstruction.layoutParams = tvInstructionParams


                        // afficher l'instruction
                        layoutInstruction.visibility = View.VISIBLE
                        // Supprimer layout de recherche
                        layoutNavigationInput.visibility = View.GONE
                        // Supprimer bouton "demarrer la navigation"
                        btnStartNavigation.visibility = View.GONE
                        // Afficher bouton "Arréter la navigation"
                        btnFinishNavigation.visibility = View.VISIBLE

                    }
                }
            }
        }

        // Arret de la navigation au clic
        findViewById<Button>(R.id.btnFinishNavigation).setOnClickListener {

            val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = 150 * resources.displayMetrics.density.toInt() // 150dp
            mapView.layoutParams = params

            // Supprimer bouton "Arreter la navigation"
            btnFinishNavigation.visibility = View.GONE

            // Afficher layout de recherche
            layoutNavigationInput.visibility = View.VISIBLE
            // Supprimer l'instruction
            tvInstruction.visibility = View.GONE
            // Afficher layout de recherche
            layoutNavigationInput.visibility = View.VISIBLE
            // Afficher bouton "demarrer la navigation"
            btnStartNavigation.visibility = View.VISIBLE
            // Afficher les filtre
            layoutFilter.visibility = View.VISIBLE

            var locationJob: Job? = null
            locationJob?.cancel()

            routePoints = emptyList()
            instructions = emptyList()
            tvDestination.text = ""
        }
    }

    /**
     * getCoordinatesFromAddress
     * @info : Geocode the address to get the coordinates
     * @param address : String
     * @call: displayAddress
     * @call: getDirections
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getCoordinatesFromAddress(address: String) {
        val geocoder = Geocoder(this)
        try {
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

    /**
     * getDirections
     * @info : Call the API to retrieve directions
     * @param currentLatLng : LatLng
     * @param destinationLatLng: LatLng
     * @call: displayVectorPath
     */
    private fun getDirections(currentLatLng: LatLng, destinationLatLng: LatLng) {
        CoroutineScope(Dispatchers.IO).launch {

            try {
                val navInstructions = mutableListOf<NavigationInstruction>()

                // Appel à l'API
                val responseTeste = navigationService.getRoute(currentLatLng, destinationLatLng)
                val ghResponse: Path? = responseTeste.paths.firstOrNull()

                if (ghResponse != null) {
                    val points = ghResponse.points
                    val coordinates = points.coordinates

                    val totalMinutes = formatTime(ghResponse.time)
                    val totalKilometers = ghResponse.distance / 1000.0


                    val coordinatesGeoPoints: List<GeoPoint> = coordinates.map { (lon, lat) ->
                        GeoPoint(lat, lon)
                    }

                    ghResponse.instructions.forEach { instr ->

                        Log.d("GPS", "3. ghResponse")
                        // Récupère les indices de début et fin dans geoPoints
                        val (startIdx, endIdx) = instr.interval
                        val arrow: Drawable?
                        val startGeoPoint = coordinatesGeoPoints[startIdx]
                        val endGeoPoint = coordinatesGeoPoints[endIdx]
                        val sign: Int? = instr.sign

                        if (sign == 6) {
                            val turnAngle: Double? = instr.turn_angle
                            arrow = getRoundaboutIconFromBearing(turnAngle)
                        }else {
                            arrow = getArrowForInstruction(sign)
                        }

                        navInstructions += NavigationInstruction(
                            message = instr.text,
                            location = startGeoPoint,
                            arrow = arrow
                        )
                        navInstructions += NavigationInstruction(
                            message = instr.text,
                            location = endGeoPoint,
                            arrow = arrow
                        )
                    }

                    instructions = navInstructions

                    displayVectorPath(coordinatesGeoPoints)
                }
            }catch (e: Exception) {
                Log.e("OtherAPI", "Erreur: $e")
            }
        }
    }

    fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        val remainingSeconds = seconds % 60

        // Format : heures:minutes:secondes
        return String.format("%02d:%02d:%02d", hours, remainingMinutes, remainingSeconds)
    }

    /**
     * getArrowForInstruction
     * @info : Display the direction arrow
     * @param sign : Code that identifies the specific navigation maneuver
     * @return : Drawable (icon)
     */
    fun getArrowForInstruction(sign: Int?): Drawable? {

        return when (sign){
            //Tout droit
            0 -> {ContextCompat.getDrawable(this, R.drawable.nav_straight_bk)}

            //left
            -3-> {ContextCompat.getDrawable(this, R.drawable.nav_left_2_bk)}// serrer
            -2 -> {ContextCompat.getDrawable(this, R.drawable.nav_left_2_bk)}// simple
            -1 -> {ContextCompat.getDrawable(this, R.drawable.nav_left_1_bk)}// leger

            //right
            3 -> {ContextCompat.getDrawable(this, R.drawable.nav_right_2_bk)}// serrer
            2 -> {ContextCompat.getDrawable(this, R.drawable.nav_right_2_bk)}// simple
            1 -> {ContextCompat.getDrawable(this, R.drawable.nav_right_1_bk)}// leger

            //Rester à gauche (garder la file de gauche)
            7 -> {ContextCompat.getDrawable(this, R.drawable.nav_left_1_bk)}

            //Rester à droite (garder la file de droite)
            8 -> {ContextCompat.getDrawable(this, R.drawable.nav_right_1_bk)}

            else -> null // Pas de flèche si l'instruction est autre
        }
    }

    /**
     * getRoundaboutIconFromBearing
     * @info : Displays the type of roundabout
     * @param bearing : current bearing
     * @return : Drawable (icon)
     */
    fun getRoundaboutIconFromBearing(bearing: Double?): Drawable? {
        val safeBearing = bearing ?: 0.0
        val angleDeg = Math.toDegrees(safeBearing)

        return when (angleDeg) {
            in -70.0..-0.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r1_bk) // N
            in -120.0..-70.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r2_bk) // NE
            in -170.0..-120.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r3_bk) // E
            in -190.0..-170.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r4_bk) // SE
            in -230.0..-190.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r5_bk) // S
            in -280.0..-230.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r6_bk) // SW
            in -340.0..-280.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r7_bk) // W
            in -360.0..-340.0 -> ContextCompat.getDrawable(this, R.drawable.nav_roundabout_r8_bk) // NW
            else -> null
        }
    }

    /**
     * displayVectorPath
     * @info : Displays the navigation vector plot
     * @param coordinates : List<GeoPoint>
     */
    private fun displayVectorPath(coordinates: List<GeoPoint>) {

        routePoints = coordinates.map { GeoPoint(it.latitude, it.longitude) }

        val roadOverlay = Polyline()
        roadOverlay.setPoints(routePoints)
        roadOverlay.color = Color.BLACK
        roadOverlay.width = 17f
        mapView.overlays.add(roadOverlay)
        mapView.overlays.add(arrowMarker)
        mapView.invalidate()
    }

    /**
     * displayAddress
     * @info : Displays the full address in the text field
     * @param destinationAddress : full address
     */
    private fun displayAddress(destinationAddress: String) {
        if (destinationAddress.isNotEmpty()) {
            tvDestination.text = "📍 $destinationAddress"
        }
    }

    /**
     * configureMap
     * @info : Map configuration (theme, zoom, center)
     * @param location : current position
     * @call : getCoordinatesFromAddress
     */
    private fun configureMap(location: GeoPoint) {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(location)
        mapView.invalidate()

        displayArrowNavigation(location)
    }

    /**
     * displayArrowNavigation
     * @info : Create a default arrow
     * @param location : current position
     */
    private fun displayArrowNavigation(location: GeoPoint) {
       if (::mapView.isInitialized && !::arrowMarker.isInitialized) {

           arrowMarker = Marker(mapView).apply {
               icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_dafault_arrow)
               setAnchor(Marker.ANCHOR_TOP, Marker.ANCHOR_TOP)
           }

           mapView.overlays.add(arrowMarker)
           arrowMarker.position = location
           mapView.controller.setCenter(location)
           mapView.invalidate()
        }
    }

    /**
     * updateArrowOverlay
     * @info : Updates arrow
     * @param position : arrow position
     */
    private fun updateArrowOverlay(position: Location) {
        val currentGeoPoint = GeoPoint(position.latitude, position.longitude)
        var bearing: Float = position.bearing

        // Update arrow icon
        arrowMarker.apply {
            icon = ContextCompat.getDrawable(this@MainActivity, arrowIcon)
        }

        animateArrowTo(currentGeoPoint)
        animateMapTo(currentGeoPoint, bearing)
        checkInstructionTrigger(currentGeoPoint)
        lastPosition = position
    }

    /**
     * animateArrowTo
     * @info : Animates arrow position
     * @param newPosition : GeoPoint
     * @param duration : Long = 1500L
     */
    private fun animateArrowTo(newPosition: GeoPoint, duration: Long = 1500L) {
        val old = lastCenter ?: newPosition

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val lat = old.latitude + (newPosition.latitude - old.latitude) * fraction
                val lon = old.longitude + (newPosition.longitude - old.longitude) * fraction
                val interpolated = GeoPoint(lat, lon)

                //arrowMarker.rotation = 0f
                arrowMarker.position = interpolated
                arrowMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.invalidate()
            }

        }

        animator.start()

    }

    /**
     * animateMapTo
     * @info : Animates map position
     * @param newPosition : GeoPoint
     * @param newBearing : Float
     * @param duration : Long = 1300L
     */
    private fun animateMapTo(newPosition: GeoPoint, newBearing: Float, duration: Long = 1300L) {
        val old = lastCenter ?: newPosition

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val lat = old.latitude + (newPosition.latitude - old.latitude) * fraction
                val lon = old.longitude + (newPosition.longitude - old.longitude) * fraction
                val interpolated = GeoPoint(lat, lon)

                mapView.mapOrientation = newBearing

                lastCenter = interpolated
                mapView.controller.setCenter(interpolated)
                mapView.invalidate()
            }
        }
        animator.start()
    }


    /**
     * checkInstructionTrigger
     * @info : Updates navigation instruction
     * @param currentLocation : GeoPoint
     */
    private fun checkInstructionTrigger(currentLocation: GeoPoint) {

        if (currentInstructionIndex >= instructions.size) return

        val currentInstruction = instructions[currentInstructionIndex]
        val instructionPoint = currentInstruction.location
        val distanceToInstruction = currentLocation.distanceToAsDouble(instructionPoint)
        val nextInstruction = instructions[currentInstructionIndex]

        if (distanceToInstruction < 80 && currentInstructionIndex < instructions.size - 1) {
            showInstruction(nextInstruction)
        }
    }

    /**
     * showInstruction
     * @info : Updates ImageView with instruction and arrow direction
     * @param nextInstruction : NavigationInstruction (message, location, arrow)
     */
    private fun showInstruction(nextInstruction: NavigationInstruction) {
        arrowImageView.setImageDrawable(nextInstruction.arrow)
        tvInstruction.text = nextInstruction.message
        tvInstruction.visibility = View.VISIBLE
    }

    /**
     * requestPermissionsIfNeeded
     * @info : Request permissions if needed
     */
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
