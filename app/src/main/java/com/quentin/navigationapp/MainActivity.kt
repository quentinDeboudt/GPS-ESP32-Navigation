package com.quentin.navigationapp

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
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
import android.os.Handler
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
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Spinner
import com.quentin.navigationapp.network.Path
import kotlinx.coroutines.Job
import org.osmdroid.util.BoundingBox
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private val navigationService = NavigationService()

    // Initialize navigation-related UI views
    private lateinit var mapView: MapView
    private lateinit var tvInstruction: TextView
    private lateinit var arrowImageView: ImageView
    private lateinit var tvDestination: TextView
    private lateinit var layoutInstruction: LinearLayout
    private lateinit var layoutControl: LinearLayout
    private lateinit var navigationTime: TextView
    private lateinit var navigationDistance: TextView
    private lateinit var btnStartNavigation: Button
    private lateinit var layoutNavigationInput: LinearLayout
    private lateinit var btnFinishNavigation: Button
    private lateinit var layoutFilter: LinearLayout

    //Navigation information:
    private var totalMinutes: String = "00:00"
    private var totalKilometers: String = "0"
    private lateinit var instructions: List<NavigationInstruction>
    private var lastPosition: Location? = null
    private var lastDisplayedInstructionIndex: Int = -1
    private var lastCenter: GeoPoint? = null

    //Navigation variables:
    private lateinit var currentPosition: GeoPoint
    private lateinit var currentDestination: GeoPoint
    private var routePoints: List<GeoPoint> = emptyList()
    private lateinit var arrowMarker: Marker
    private var arrowIcon: Int = R.drawable.ic_arrow_motorbike
    private var vehicle: String = "car"
    private var weightings: String = "fastest"
    private var lastVectorPath: MutableList<GeoPoint> = mutableListOf()
    private var traveledPathPolyline: Polyline? = null
    private var isNavigating = false
    private var currentRoutePolyline: Polyline? = null

    // Dispatcher I/O pour les Flows
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // Gives the normalized angle difference between [-180, +180]
    private fun normalizeBearingDiff(diff: Double): Double =
        ((diff + 540) % 360) - 180

    // Data class to store navigation instructions
    data class NavigationInstruction(
        val message: String,
        val location: GeoPoint,
        val arrow: Drawable? = null
    )

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
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
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
        btnStartNavigation = findViewById<Button>(R.id.btnStartNavigation)
        layoutNavigationInput = findViewById<LinearLayout>(R.id.layout_navigation_Input)
        btnFinishNavigation = findViewById<Button>(R.id.btnFinishNavigation)
        layoutFilter = findViewById<LinearLayout>(R.id.layout_filter)

        // Hide buttons
        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.GONE
        layoutInstruction.visibility = View.GONE
        layoutControl.visibility = View.GONE

        getCurrentPosition()

        // Spinner config (bike? motorcycle? ...)
        val transportSpinner = findViewById<Spinner>(R.id.transport_spinner)
        val iconsTransport = listOf(R.drawable.ic_motorbike, R.drawable.ic_scooter, R.drawable.ic_motocross, R.drawable.ic_electric_scooter, R.drawable.ic_car)

        val transportAdapter = object : ArrayAdapter<Int>(this, R.layout.item_icon_only_spinner, iconsTransport) {
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
        transportSpinner.adapter = transportAdapter

        // Spinner config (speed? slow? ...)
        val speedSpinner = findViewById<Spinner>(R.id.speed_spinner)
        val iconsSpeed = listOf(R.drawable.ic_speed_logo, R.drawable.ic_slow_logo)

        val speedAdapter = object : ArrayAdapter<Int>(this, R.layout.item_icon_speed_spinner, iconsSpeed) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val imageView = LayoutInflater.from(context).inflate(R.layout.item_icon_speed_spinner, parent, false) as ImageView
                imageView.setImageResource(getItem(position)!!)
                return imageView
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val imageView = LayoutInflater.from(context).inflate(R.layout.item_icon_speed_spinner, parent, false) as ImageView
                imageView.setImageResource(getItem(position)!!)
                return imageView
            }
        }
        speedSpinner.adapter = speedAdapter

        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedIcon = iconsTransport[position]
                var newPosition: Int = -1

                when (selectedIcon) {
                    R.drawable.ic_motorbike -> {
                        arrowIcon = R.drawable.ic_arrow_motorbike
                        newPosition = 0
                        vehicle = "car"
                    }
                    R.drawable.ic_scooter -> {
                        arrowIcon = R.drawable.ic_arrow_scooter
                        newPosition = 0
                        vehicle = "scooter"
                    }
                    R.drawable.ic_motocross -> {
                        arrowIcon = R.drawable.ic_bike_arrow
                        newPosition = 1
                        vehicle = "mtb"
                    }
                    R.drawable.ic_electric_scooter -> {
                        arrowIcon = R.drawable.ic_arrow_scooter
                        newPosition = 0
                        vehicle = "bike"
                    }
                    R.drawable.ic_car -> {
                        arrowIcon = R.drawable.ic_car_arrow
                        newPosition = 2
                        vehicle = "car"
                    }

                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
        }

        speedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedIcon = iconsSpeed[position]
                var newPosition: Int = -1

                when (selectedIcon) {
                    R.drawable.ic_speed_logo -> {
                        newPosition = 0
                        weightings = "fastest"
                    }
                    R.drawable.ic_slow_logo -> {
                        newPosition = 0
                        weightings = "eco"
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
        }


        //Recherche de l'itineraire
        findViewById<Button>(R.id.searchNavigationButton).setOnClickListener {

            getCurrentPosition()

            // Réinitialise la trace effective
            lastVectorPath.clear()
            // Retire l’ancien tracé “véhicule passé”
            traveledPathPolyline?.let {
                mapView.overlays.remove(it)
                traveledPathPolyline = null
            }
            mapView.invalidate()

            // Masquer le clavier proprement
            val imm = ContextCompat.getSystemService(this, InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(tvDestination.windowToken, 0)

            tvDestination.clearFocus()

            val destinationAddress = tvDestination.text.toString()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (destinationAddress.isNotEmpty()) {
                    mapView.overlays.clear()
                    routePoints = emptyList()
                    instructions = emptyList()
                    tvDestination.text = ""
                    getCoordinatesFromAddress(destinationAddress)

                    // Afficher bouton "demarrer la navigation"
                    layoutControl.visibility = View.VISIBLE
                    btnStartNavigation.visibility = View.VISIBLE

                } else {
                    Toast.makeText(this, "Veuillez entrer une destination", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Lancement de la navigation au clic
        var navigationJob: Job? = null
        requestPermissionsIfNeeded {
            findViewById<Button>(R.id.btnStartNavigation).setOnClickListener {

                navigationStartView()
                navigationisStarted(true)

                navigationJob = lifecycleScope.launch {
                    locationFlow.collect {

                        currentPosition = GeoPoint(it.latitude, it.longitude)

                        updateArrowOverlay(it)

                        if (isFarFromRoute(currentPosition, routePoints, maxDistanceMeters = 100.0)) {
                            routeRecalculation()
                        }
                        displayLastPathPolyline()
                    }
                }
            }
        }

        // Arret de la navigation au clic
        findViewById<Button>(R.id.btnFinishNavigation).setOnClickListener {

            mapView.overlays.remove(arrowMarker)

            navigationisStarted(false)
            navigationJob?.cancel()
            navigationJob = null

            displayArrowNavigation(currentPosition)

            navigationStopView()
        }
    }

    /**
     * navigationStartView
     */
    private fun navigationStartView() {

        val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = 0
        mapView.layoutParams = params
        mapView.controller.setZoom(18.0)

        val tvInstructionParams = layoutInstruction.layoutParams as ViewGroup.MarginLayoutParams
        tvInstructionParams.topMargin = 15 * resources.displayMetrics.density.toInt()
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

    private fun navigationStopView() {

        val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = 200 * resources.displayMetrics.density.toInt() // 150dp
        mapView.layoutParams = params

        // Supprimer bouton "Arreter la navigation"
        btnFinishNavigation.visibility = View.GONE
        // Afficher layout de recherche
        layoutNavigationInput.visibility = View.VISIBLE

        layoutInstruction.visibility = View.GONE
        // Afficher layout de recherche
        layoutNavigationInput.visibility = View.VISIBLE
        // Afficher bouton "demarrer la navigation"
        btnStartNavigation.visibility = View.VISIBLE
        // Afficher les filtre
        layoutFilter.visibility = View.VISIBLE
    }

    private fun displayLastPathPolyline(){
        lastVectorPath.add(currentPosition)

        if (traveledPathPolyline == null) {
            traveledPathPolyline = Polyline().apply {
                color = Color.BLACK
                width = 10f
                mapView.overlays.add(this)
            }
        }
        traveledPathPolyline!!.setPoints(lastVectorPath)

        mapView.invalidate()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getCurrentPosition() {

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                currentPosition = GeoPoint(lat, lon)
                configureMap(GeoPoint(lat, lon))
            } else {
                Toast.makeText(this, "Impossible d’obtenir la localisation", Toast.LENGTH_SHORT).show()
            }
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

                currentDestination = GeoPoint(destinationLatLng.latitude, destinationLatLng.longitude)

                displayAddress(finishAddress[0].getAddressLine(0).toString())
                getDirections(destinationLatLng)
            } else {
                Toast.makeText(this, "Adresse non trouvée", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Erreur de géocodification", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Calcule la distance (en mètres) du point 'current' au segment [start–end].
     * La projection est faite en coordonnées métriques pour éviter l’imprécision des degrés.
     */
    private fun distanceToSegment(
        current: GeoPoint,
        start: GeoPoint,
        end: GeoPoint
    ): Double {
        val R = 6_371_000.0  // Rayon moyen de la Terre en mètres

        // Convertis lat/lon en radians
        val lat0 = Math.toRadians(current.latitude)
        val lon0 = Math.toRadians(current.longitude)
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)

        // Pour limiter l’erreur en longitude, on approche avec la latitude moyenne
        val meanLat = (lat1 + lat2) / 2
        val cosMeanLat = cos(meanLat)

        // Coordonnées cartésiennes (approximation equirectangulaire)
        val x0 = lon0 * cosMeanLat * R
        val y0 = lat0 * R
        val x1 = lon1 * cosMeanLat * R
        val y1 = lat1 * R
        val x2 = lon2 * cosMeanLat * R
        val y2 = lat2 * R

        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) {
            // Segment réduit à un point
            return current.distanceToAsDouble(start)
        }

        // Projection scalaire de (P−A) sur (B−A)
        val t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx*dx + dy*dy)
        val tClamped = min(1.0, max(0.0, t))

        // Point projeté en métrique
        val projX = x1 + tClamped * dx
        val projY = y1 + tClamped * dy

        // Distance euclidienne en mètre
        val distX = x0 - projX
        val distY = y0 - projY
        return kotlin.math.hypot(distX, distY)
    }

    /**
     * Vérifie si la position est à plus de maxDistanceMeters
     * de tous les segments du tracé.
     */
    private fun isFarFromRoute(
        current: GeoPoint,
        routePoints: List<GeoPoint>,
        maxDistanceMeters: Double = 30.0
    ): Boolean {
        if (routePoints.size < 2) return true

        // Calcule la plus petite distance aux segments successifs
        val minDist = routePoints
            .zipWithNext().minOfOrNull { (start, end) ->
                distanceToSegment(current, start, end)
            } ?: Double.MAX_VALUE

        return minDist > maxDistanceMeters
    }

    /**
     * RouteRecalculation
     * @info: Dynamic route recalculation
     */
    private fun routeRecalculation() {
        val currentDestinationLatLng = LatLng(currentDestination.latitude, currentDestination.longitude)
        getDirections(currentDestinationLatLng)
    }

    /**
     * getDirections
     * @info : Call the API to retrieve directions
     * @param currentLatLng : LatLng
     * @param destinationLatLng: LatLng
     * @call: displayVectorPath
     */
    private fun getDirections(destinationLatLng: LatLng) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentLatLng = LatLng(currentPosition.latitude, currentPosition.longitude)
                val navInstructions = mutableListOf<NavigationInstruction>()
                totalMinutes = "0"
                totalKilometers = "0"

                // Appel à l'API
                val responseTeste = navigationService.getRoute(currentLatLng, destinationLatLng, vehicle, weightings)
                val ghResponse: Path? = responseTeste.paths.firstOrNull()

                if (ghResponse != null) {
                    val points = ghResponse.points
                    val coordinates = points.coordinates

                    totalMinutes = formatTime(ghResponse.time)
                    val distanceKm = ghResponse.distance / 1000.0
                    totalKilometers = String.format("%.1f", distanceKm) + " km"


                    val coordinatesGeoPoints: List<GeoPoint> = coordinates.map { (lon, lat) ->
                        GeoPoint(lat, lon)
                    }

                    ghResponse.instructions.forEach { instr ->

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
                Log.e("API", "Erreur: $e")
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
     fun displayVectorPath(coordinates: List<GeoPoint>) {
        routePoints = coordinates.map { GeoPoint(it.latitude, it.longitude)}

        // SUPPRIMER l’ancien tracé s’il existe
        currentRoutePolyline?.let { oldPolyline ->
            mapView.overlays.remove(oldPolyline)
            // si tu as des marqueurs associés, fais de même
            mapView.invalidate()
        }

        // CRÉER et AJOUTER le nouveau tracé
        val newPolyline = Polyline().apply {
            setPoints(routePoints)
            width = 12f
            color = Color.BLACK
        }
        mapView.overlays.add(newPolyline)
        currentRoutePolyline = newPolyline

        if (!isNavigating) {
            val bbox = BoundingBox.fromGeoPointsSafe(routePoints)

            Handler(Looper.getMainLooper()).postDelayed({
                mapView.zoomToBoundingBox(bbox, true, 300)

                navigationTime.text = totalMinutes
                navigationDistance.text = totalKilometers

            }, 300)
        }
        mapView.overlays.add(arrowMarker)
        mapView.invalidate()
    }

    // 2️⃣ Quand tu démarres / arrêtes la navigation, mets à jour ton flag :
    fun navigationisStarted(value: Boolean) {
        isNavigating = value



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
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
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


        if (::mapView.isInitialized && ::arrowMarker.isInitialized) {
           mapView.overlays.remove(arrowMarker)
           mapView.invalidate()
        }

        arrowMarker = Marker(mapView).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_dafault_arrow)
            setAnchor(Marker.ANCHOR_TOP, Marker.ANCHOR_TOP)
        }

        mapView.overlays.add(arrowMarker)
        arrowMarker.position = location
        mapView.controller.setCenter(location)
        mapView.invalidate()
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
        checkInstructionTrigger(currentGeoPoint, bearing)
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

                mapView.mapOrientation = -newBearing

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
    private fun checkInstructionTrigger(
        currentLocation: GeoPoint,
        deviceHeading: Float
    ) {
        if (instructions.isEmpty()) return

        // seuils personnalisables
        val announceDistance = 80.0   // annonce à 80 m
        val minDistanceToFire = 20.0  // changement d’instruction à 20 m
        val maxAngleDiff = 90.0       // instruction devant dans ±90°

        // 1) on filtre les instructions à venir
        val upcoming = instructions
            .mapIndexed { idx, instr -> idx to instr }
            .filter { (idx, _) -> idx > lastDisplayedInstructionIndex }
            .filter { (_, instr) ->
                // calcul du bearing de la position actuelle vers l’instruction
                val bearingToInstr = currentLocation
                    .toLocation()
                    .bearingTo(instr.location.toLocation())
                    .toDouble()
                // ne garder que celles dont l’angle relatif est dans ±maxAngleDiff
                kotlin.math.abs(normalizeBearingDiff(bearingToInstr - deviceHeading)) < maxAngleDiff
            }

        if (upcoming.isEmpty()) {
            tvInstruction.text = ""
            arrowImageView.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.nav_straight_bk)
            )
            return
        }

        // 2) trouver la plus proche à vol d’oiseau
        val (closestIdx, closestInstr) = upcoming.minByOrNull { (_, instr) ->
            currentLocation.distanceToAsDouble(instr.location)
        }!!

        val distance = currentLocation.distanceToAsDouble(closestInstr.location)

        // 3) logique d’annonce et de passage à la suivante
        when {
            // pré-annonce
            distance < announceDistance && closestIdx == lastDisplayedInstructionIndex + 1 -> {
                showInstruction(closestInstr)
                // on ne change pas l’index tout de suite : on attend le déclencheur “réel”
            }
            // instruction “effectuée”
            distance < minDistanceToFire -> {
                lastDisplayedInstructionIndex = closestIdx
                // si tu veux annoncer la suivante immédiatement :
                if (lastDisplayedInstructionIndex + 1 < instructions.size) {
                    showInstruction(instructions[lastDisplayedInstructionIndex + 1])
                }
            }
            else -> {
                var distanceKmAround = "0"
                if(distance >= 1000.0){
                    val distanceKm = distance / 1000.0
                    distanceKmAround = String.format("%.1f", distanceKm) + " km"
                }
                if(distance < 1000.0){
                    distanceKmAround = String.format("%.0f", distance) + " m"
                }

                tvInstruction.text = distanceKmAround
                arrowImageView.setImageDrawable(
                    ContextCompat.getDrawable(this, R.drawable.nav_straight_bk)
                )
            }
        }
    }

    /**
     * toLocation
     * @info : Convert GeoPoint to Location
     */
    private fun GeoPoint.toLocation(): Location {
        val loc = Location("osmdroid")
        loc.latitude = this.latitude
        loc.longitude = this.longitude
        return loc
    }

    /**
     * showInstruction
     * @info : Updates ImageView with instruction and arrow direction
     * @param nextInstruction : NavigationInstruction (message, location, arrow)
     */
    private fun showInstruction(nextInstruction: NavigationInstruction) {
        arrowImageView.setImageDrawable(nextInstruction.arrow)
        tvInstruction.text = nextInstruction.message
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
