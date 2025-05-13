package com.quentin.navigationapp

import android.Manifest
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
import com.quentin.navigationapp.model.DirectionsResponse
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Spinner


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
    private var arrowIcon: Int = R.drawable.ic_arrow_motorbike

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
                Log.d("GPS", "Géolocalisation mise à jours..")
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
        val btnStartNavigation = findViewById<Button>(R.id.btnStartNavigation)
        val layoutNavigationInput = findViewById<LinearLayout>(R.id.layout_navigation_Input)
        val btnFinishNavigation = findViewById<Button>(R.id.btnFinishNavigation)
        val layoutFilter = findViewById<LinearLayout>(R.id.layout_filter)

        // Hide buttons
        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.GONE
        tvInstruction.visibility = View.GONE

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
                    lifecycleScope.launch {
                        locationFlow.collect {
                            updateArrowOverlay(it)

                            val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
                            params.topMargin = 0
                            mapView.layoutParams = params

                            val tvInstructionParams = tvInstruction.layoutParams as ViewGroup.MarginLayoutParams
                            params.topMargin = 5 * resources.displayMetrics.density.toInt()
                            tvInstruction.layoutParams = tvInstructionParams

                            val arrowImageViewParams = arrowImageView.layoutParams as ViewGroup.MarginLayoutParams
                            params.topMargin = 5 * resources.displayMetrics.density.toInt()
                            arrowImageView.layoutParams = arrowImageViewParams

                            // afficher l'instruction
                            tvInstruction.visibility = View.VISIBLE
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
        }

        // Arret de la navigation au clic
        findViewById<Button>(R.id.btnFinishNavigation).setOnClickListener {

            val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = 150 * resources.displayMetrics.density.toInt() // 150dp
            mapView.layoutParams = params

            // Supprimer bouton "Arreter la navigation"
            btnFinishNavigation.visibility = View.GONE
            // Supprimer l'instruction
            tvInstruction.visibility = View.GONE
            // Afficher layout de recherche
            layoutNavigationInput.visibility = View.VISIBLE
            // Afficher bouton "demarrer la navigation"
            btnStartNavigation.visibility = View.VISIBLE
            // Afficher les filtre
            layoutFilter.visibility = View.VISIBLE

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
                // Liste pour stocker les instructions et les positions
                val instructionsList = mutableListOf<NavigationInstruction>()
                val response = navigationService.getDirections(currentLatLng, destinationLatLng)
                val feature = response.features.firstOrNull() // Prendre la première feature
                val segment = feature?.properties?.segments?.firstOrNull() // Récupère le premier segment
                val steps = segment?.steps ?: emptyList() // Récupère les étapes
                val features = response.features // Accès aux features

                // Itération sur les features
                for (feature in features) {
                    val segments = feature.properties.segments

                    segments.forEach { segment ->
                        // Récupère le tracé vectoriel
                        val coordinates = feature.geometry.coordinates
                        var exitNumber: Int? = null

                        // Itération sur les étapes pour récupérer l'instruction et les coordonnées
                        for (step in steps) {
                            val instruction = step.instruction // Récupère l'instruction de l'étape
                            val wayPoints = step.way_points // Récupère les way_points
                            val startCoord = coordinates.getOrNull(wayPoints[0])
                            val endCoord = coordinates.getOrNull(wayPoints[1])
                            exitNumber = step.exit_number // Récupère le numéro de sortie

                            if (startCoord != null && endCoord != null) {
                                val startGeoPoint = GeoPoint(startCoord[1], startCoord[0])
                                val endGeoPoint = GeoPoint(endCoord[1], endCoord[0])

                                val bearing = calculateBearing(startGeoPoint, endGeoPoint)
                                //val afterStartGeoPoint = offsetGeoPoint(startGeoPoint, (bearing + 180) % 360, 10.0)

                                val geoPoints = feature.geometry.coordinates.map { coord ->
                                    GeoPoint(coord[1], coord[0])
                                }
                                val entryIndex = findClosestIndex(startGeoPoint, geoPoints)

                                val before = geoPoints.getOrNull(entryIndex) ?: geoPoints[entryIndex]
                                val after = geoPoints.getOrNull(entryIndex + 5) ?: geoPoints.last()
                                val angle = angleBetweenThreePoints(before, geoPoints[entryIndex], after)

                                if(exitNumber != null) {

                                    val startMaker = Marker(mapView)
                                    startMaker.position = before
                                    startMaker.title = "Start Point"
                                    startMaker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    mapView.overlays.add(startMaker)

                                    val middleMarker = Marker(mapView)
                                    middleMarker.position = after
                                    middleMarker.title = "$angle"
                                    middleMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    mapView.overlays.add(middleMarker)
                                }


                                // Ajouter l'instruction et la coordonnée à la liste
                                instructionsList.add(
                                    NavigationInstruction(
                                        instruction,
                                        startGeoPoint,
                                        if(exitNumber != null){
                                            getRoundaboutIconFromBearing(bearing)
                                        } else {
                                            getArrowForInstruction(instruction)
                                        }

                                    )
                                )
                                instructionsList.add(
                                    NavigationInstruction(
                                        instruction,
                                        endGeoPoint,
                                        if(exitNumber != null){
                                            getRoundaboutIconFromBearing(bearing)
                                        } else {
                                            getArrowForInstruction(instruction)
                                        }
                                    )
                                )
                            }
                        }
                    }
                }

                instructions = instructionsList

                displayVectorPath(response)

            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Erreur lors de la récupération des directions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

//-----------------------------------------------------------------------------------------------------
    fun findClosestIndex(target: GeoPoint, list: List<GeoPoint>): Int {
        return list.withIndex().minByOrNull { (_, pt) ->
            pt.distanceToAsDouble(target)
        }?.index ?: 0
    }

    fun angleBetweenThreePoints(before: GeoPoint, center: GeoPoint, after: GeoPoint): Double {
        val v1x = before.longitude - center.longitude
        val v1y = before.latitude - center.latitude
        val v2x = after.longitude - center.longitude
        val v2y = after.latitude - center.latitude

        val dot = v1x * v2x + v1y * v2y
        val det = v1x * v2y - v1y * v2x

        val angle = atan2(det, dot) // angle signé
        return Math.toDegrees(angle)
    }

    // Fonction pour calculer le bearing entre deux points
    fun calculateBearing(start: GeoPoint, end: GeoPoint): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)

        val deltaLon = lon2 - lon1
        val y = Math.sin(deltaLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon)

        val bearing = Math.toDegrees(Math.atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }
//-----------------------------------------------------------------------------------------------------


    /***
     * getArrowForInstruction
     * @info : Display the direction arrow
     * @param instruction : current instruction
     * @return : Drawable (icon)
     */
    fun getArrowForInstruction(instruction: String): Drawable? {
        val slightRight = listOf("slight", "right")
        val slightLeft = listOf("slight", "right")

        return when {
            slightRight.any { keyword -> instruction.contains(keyword, ignoreCase = true) } -> {
                ContextCompat.getDrawable(this, R.drawable.nav_right_1_bk)
            }
            slightLeft.any { keyword -> instruction.contains(keyword, ignoreCase = true) } -> {
                ContextCompat.getDrawable(this, R.drawable.nav_left_1_bk)
            }
            instruction.contains("right", ignoreCase = true) -> {
                ContextCompat.getDrawable(this, R.drawable.nav_right_2_bk)
            }
            instruction.contains("left", ignoreCase = true) -> {
                ContextCompat.getDrawable(this, R.drawable.nav_left_2_bk)
            }
            instruction.contains("straight", ignoreCase = true) -> {
                ContextCompat.getDrawable(this, R.drawable.nav_straight_bk)
            }

            else -> null // Pas de flèche si l'instruction est autre
        }
    }

    /***
     * getRoundaboutIconFromBearing
     * @info : Displays the type of roundabout
     * @param bearing : current bearing
     * @return : Drawable (icon)
     */
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

    /***
     * displayVectorPath
     * @info : Displays the navigation vector plot
     * @param response : All navigation geopoints
     */
    private fun displayVectorPath(response: DirectionsResponse) {

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

    /***
     * displayAddress
     * @info : Displays the full address in the text field
     * @param destinationAddress : full address
     */
    private fun displayAddress(destinationAddress: String) {
        if (destinationAddress.isNotEmpty()) {
            tvDestination.text = "📍 $destinationAddress"
        }
    }

    /***
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

    /***
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

    /***
     * updateArrowOverlay
     * @info : Updates arrow position & rotation
     * @param position : arrow position
     */
    private fun updateArrowOverlay(position: Location) {
        val currentGeoPoint = GeoPoint(position.latitude, position.longitude)
        var bearing: Float = position.bearing

        // Update arrow icon
        arrowMarker.apply {
            icon = ContextCompat.getDrawable(this@MainActivity, arrowIcon)
        }
        mapView.invalidate()

        // Updates the arrow position
        arrowMarker.position = currentGeoPoint
        arrowMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        // Updates map & arrow orientation
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
    private var instructionAffichee = false

    private fun checkInstructionTrigger(currentLocation: GeoPoint) {

        if (currentInstructionIndex >= instructions.size) return

        val nextInstruction = instructions[currentInstructionIndex]
        val distance = currentLocation.distanceToAsDouble(nextInstruction.location)

        // 🔹 Étape 1 : afficher l’instruction quand on approche (mais une seule fois)
        if (distance < 100.0 && !instructionAffichee) {
            showInstruction(nextInstruction)
            instructionAffichee = true
        }

        //TODO: Ne fonctionne pas parfaitement!
        /*
        // 🔹 Étape 2 : quand on s’éloigne, on passe à la suivante et on vide l’affichage
        if (instructionAffichee && distance > 120.0) {
            instructionAffichee = false
            currentInstructionIndex++
            tvInstruction.text = ""

            // Affiche l’état "neutre" : flèche tout droit
            arrowImageView.setImageDrawable(getArrowForInstruction("straight"))
            tvInstruction.text = "Continuez tout droit" // ou "Continuez tout droit"
        }*/
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
