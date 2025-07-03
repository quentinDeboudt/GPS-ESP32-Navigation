package com.quentin.navigationapp.ui.fragments.map

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.quentin.navigationapp.data.NavigationService
import com.quentin.navigationapp.model.BleData
import com.quentin.navigationapp.model.NavigationInstruction
import com.quentin.navigationapp.model.Profile
import com.quentin.navigationapp.model.VehicleSubType
import com.quentin.navigationapp.network.Path
import com.quentin.navigationapp.network.SpeedSegment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*
import com.quentin.navigationapp.util.BluetoothManager
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openjdk.tools.javac.util.Position
import org.osmdroid.api.IGeoPoint

class MapFragment : Fragment() {

    // Widgets du layout
    private var loadingDialog: AlertDialog? = null
    private lateinit var mapView: MapView
    private lateinit var tvDestination: TextView
    private lateinit var btnStartNavigation: Button
    private lateinit var layoutNavigationInput: ConstraintLayout
    private lateinit var btnFinishNavigation: Button
    private lateinit var searchNavigationButton: Button

    //layout to device connexion
    private lateinit var layoutConnexion: LinearLayout
    private lateinit var iconDevice: ImageView
    private lateinit var tvConnectDevice: TextView

    //layout to information navigation
    private lateinit var layoutControl: LinearLayout
    private lateinit var navigationTime: TextView
    private lateinit var navigationDistance: TextView

    // Navigation variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private val navigationService = NavigationService()
    private var totalMinutes: Long = 0
    private var totalKilometers: Double = 0.0
    private lateinit var instructions: List<NavigationInstruction>
    private var lastPosition: Location? = null
    private var lastDisplayedInstructionIndex: Int = -1
    private var lastCenter: GeoPoint? = null
    private var speedSegments: List<SpeedSegment> = emptyList()
    private var lastDirection: Int? = null
    private var lastDistanceBeforeDirection: Double? = null
    private var lastKmRemaining: Int? = null
    private var lastMinRemaining: Int? = null
    private var simplifiedPath: List<GeoPoint> = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 0.0)
    )
    private lateinit var lastSimplifiedPath: List<GeoPoint>
    private var lastSentIndex: Int = 0
    private val chunkSize = 20
    private var firstChunkSent = false

    private lateinit var currentPosition: GeoPoint
    private lateinit var currentDestination: GeoPoint
    private var routePoints: List<GeoPoint> = emptyList()
    private lateinit var arrowMarker: Marker
    private var arrowIcon: Int = com.quentin.navigationapp.R.drawable.ic_arrow_motorbike
    private var vehicle: String = "car"
    private var weightings: String = "fastest"
    private var lastVectorPath: MutableList<GeoPoint> = mutableListOf()
    private var traveledPathPolyline: Polyline? = null
    private var isNavigating = false
    private var currentRoutePolyline: Polyline? = null
    private var currentBorderPolyline: Polyline? = null
    private var currentProfile: Profile? = null
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val PREFS_NAME = "app_navigation_prefs"
    private val KEY_PROFILES_JSON = "profiles_json"
    private val KEY_CURRENT_PROFILE = "current_profile"

    /**
     * loadProfilesFromPrefs
     * Load the profiles from the shared preferences.
     * @param context The context of the activity.
     * @return A list of profiles.
     */
    private fun loadProfilesFromPrefs(context: Context): List<Profile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<Profile>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            val subTypeObj = obj.getJSONObject("subType")
            val subType = VehicleSubType(
                label = subTypeObj.getString("label"),
                routingType = subTypeObj.getString("routingType")
            )

            list += Profile(
                name = obj.getString("name"),
                type = obj.getString("type"),
                subType = subType,
                consumption = obj.getDouble("consumption")
            )
        }
        return list
    }

    /**
     * loadCurrentProfileName
     * Load the current profile name from the shared preferences.
     * @param context The context of the activity.
     * @return The name of the current profile.
     */
    private fun loadCurrentProfileName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_PROFILE, null)
    }

    /**
     * getCurrentProfile
     * get the current profile from the shared preferences.
     * @param context The context of the activity.
     * @return The current profile.
     */
    private fun getCurrentProfile(context: Context): Profile? {
        val currentName = loadCurrentProfileName(context) ?: return null
        return loadProfilesFromPrefs(context).firstOrNull { it.name == currentName }
    }

    private fun showLoadingDialog() {
        if (loadingDialog?.isShowing == true) return

        val builder = AlertDialog.Builder(requireContext())
        val inflater = layoutInflater
        val view = inflater.inflate(com.quentin.navigationapp.R.layout.dialog_loading, null)
        builder.setView(view)
        builder.setCancelable(false)
        loadingDialog = builder.create()
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    /**
     * locationFlow
     * Get the current location as a Flow.
     * @return A Flow emitting the current location.
     */
    private val locationFlow: Flow<Location> by lazy {
        callbackFlow @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                close(SecurityException("Permission ACCESS_FINE_LOCATION non accordée"))
                return@callbackFlow
            }

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L) // 0,5 second
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { trySend(it) }
                }
            }

            try {
                fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
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
     * Called when the fragment is first created.
     * @param savedInstanceState If the fragment is being re-created from a previous saved state, this is the state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configuration OSMdroid (obtenir SharedPreferences)
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        )
    }

    /**
     * onCreateView
     * Called to have the fragment instantiate its user interface view.
     * @param inflater
     * @param container
     * @param savedInstanceState
     * @return Return the View for the fragment's UI, or null.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(com.quentin.navigationapp.R.layout.map_page, container, false)
    }

    /**
     * onViexCreated
     * Called when the view is created.
     * @param view The view.
     * @param savedInstanceState If the fragment is being re-created from a previous saved state, this is the state.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Récupérer l’objet Profile actif
        currentProfile = getCurrentProfile(requireContext())
        if (currentProfile != null) {

            Toast.makeText(
                requireContext(),
                "Profil actuel: ${currentProfile!!.name}",
                Toast.LENGTH_SHORT
            ).show()

            vehicle = currentProfile!!.subType.routingType

        } else {
            Toast.makeText(requireContext(), "Aucun profil actif détecté", Toast.LENGTH_SHORT).show()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mapView = view.findViewById(com.quentin.navigationapp.R.id.map)
        tvDestination = view.findViewById(com.quentin.navigationapp.R.id.etDestination)
        layoutControl = view.findViewById(com.quentin.navigationapp.R.id.layout_control)
        layoutConnexion = view.findViewById(com.quentin.navigationapp.R.id.layout_connexion)
        iconDevice = view.findViewById(com.quentin.navigationapp.R.id.icon_device)
        tvConnectDevice = view.findViewById(com.quentin.navigationapp.R.id.tv_connect_device)
        navigationTime = view.findViewById(com.quentin.navigationapp.R.id.navigation_time)
        navigationDistance = view.findViewById(com.quentin.navigationapp.R.id.navigation_distance)
        btnStartNavigation = view.findViewById(com.quentin.navigationapp.R.id.btnStartNavigation)
        layoutNavigationInput = view.findViewById(com.quentin.navigationapp.R.id.layout_navigation_input)
        btnFinishNavigation = view.findViewById(com.quentin.navigationapp.R.id.btnFinishNavigation)
        searchNavigationButton = view.findViewById(com.quentin.navigationapp.R.id.searchNavigationButton)

        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.GONE
        layoutControl.visibility = View.GONE

        getCurrentPosition()
        deviceIsConnected()

        //button search navigation
        searchNavigationButton.setOnClickListener {
            getCurrentPosition()
            lastVectorPath.clear()
            traveledPathPolyline?.let {
                mapView.overlays.remove(it)
                traveledPathPolyline = null
            }
            mapView.invalidate()

            // Hide keyboard
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(tvDestination.windowToken, 0)
            tvDestination.clearFocus()

            val destinationAddress = tvDestination.text.toString().trim()
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (destinationAddress.isNotEmpty()) {
                    mapView.overlays.clear()
                    routePoints = emptyList()
                    instructions = emptyList()
                    tvDestination.text = ""
                    getCoordinatesFromAddress(destinationAddress)

                    layoutControl.visibility = View.VISIBLE
                    btnStartNavigation.visibility = View.VISIBLE
                } else {
                    Toast.makeText(requireContext(), "Veuillez entrer une destination", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Launch navigation
        var navigationJob: Job? = null
        requestPermissionsIfNeeded {
            btnStartNavigation.setOnClickListener {
                showLoadingDialog()
                navigationStartView()
                navigationisStarted(true)
                var lastMaxSpeed: Int? = null

                navigationJob = lifecycleScope.launch {
                    locationFlow.collect { location ->
                        currentPosition = GeoPoint(location.latitude, location.longitude)

                        updateArrowOverlay(location)

                        if (isFarFromRoute(currentPosition, routePoints)) {
                            routeRecalculation()
                        }

                        val maxSpeed = getClosestPointWithSpeed(
                            LatLng(currentPosition.latitude,currentPosition.longitude),
                            routePoints).speed

                        Log.d("debugSendData", "Verification du VectorPath...")
                        handleCurrentPositionAndPathUpdate()

                        if (BluetoothManager.isConnected()) {
                            if (maxSpeed != null && maxSpeed != lastMaxSpeed) {
                                lastMaxSpeed = maxSpeed
                                delay(1000)
                                BluetoothManager.sendData(BleData.SpeedLimit(maxSpeed))
                            }

                            val lastLoc = lastPosition?: GeoPoint(0.0, 0.0).toLocation()
                            val currentLoc = currentPosition.toLocation()

                            val distance = lastLoc.distanceTo(currentLoc)

                            if (distance >= 5f) { // Seuil : 5 mètres
                                lastPosition = currentPosition.toLocation()
                                BluetoothManager.sendData(BleData.CurrentPosition(currentLoc.latitude, currentLoc.longitude))

                                //handleCurrentPositionAndPathUpdate()
                            }

                        } else {

                            deviceIsConnected()
                        }
                    }
                }
            }
        }

        // Stop navigation
        btnFinishNavigation.setOnClickListener {
            mapView.overlays.remove(arrowMarker)
            navigationisStarted(false)
            navigationJob?.cancel()
            navigationJob = null
            displayArrowNavigation(currentPosition)
            navigationStopView()
        }
    }

    var currentChunk: List<List<Double>> = emptyList()

    fun handleCurrentPositionAndPathUpdate() {
        val thresholdMeters = 30.0
        val nearEndThreshold = 5.0
        var closestIndex = -1
        var minDistance = Double.MAX_VALUE

        // Trouver le point le plus proche dans le chemin complet
        simplifiedPath.forEachIndexed { index, point ->
            val loc = Location("").apply {
                latitude = point.latitude
                longitude = point.longitude
            }
            val currentLoc = Location("").apply {
                latitude = currentPosition.latitude
                longitude = currentPosition.longitude
            }
            val dist = currentLoc.distanceTo(loc)
            if (dist < minDistance) {
                minDistance = dist.toDouble()
                closestIndex = index
            }
        }

        // Si c’est la première fois, on envoie le premier chunk
        if (!firstChunkSent) {
            currentChunk = getNextChunkFromFullPath()

            val currentChunkJson = JSONObject().apply {
                JSONArray().apply {
                    currentChunk.forEach { point ->
                        put(JSONArray(listOf(point[0], point[1])))
                    }
                }
            }
            BluetoothManager.sendData(BleData.VectorPath(currentChunkJson))
            firstChunkSent = true
        }

        if (minDistance < thresholdMeters && currentChunk.isNotEmpty()) {
            val currentLoc = Location("").apply {
                latitude = currentPosition.latitude
                longitude = currentPosition.longitude
            }
            val chunkEndPoint = currentChunk.last()
            val chunkEndLoc = Location("").apply {
                latitude = chunkEndPoint[0]
                longitude = chunkEndPoint[1]
            }
            val distanceToChunkEnd = currentLoc.distanceTo(chunkEndLoc)

            if (distanceToChunkEnd <= nearEndThreshold) {
                // Envoie le chunk suivant seulement ici
                currentChunk = getNextChunkFromFullPath()
                if (currentChunk.isNotEmpty()) {
                    val currentChunkJson = JSONObject().apply {
                        JSONArray().apply {
                            currentChunk.forEach { point ->
                                put(JSONArray(listOf(point[0], point[1])))
                            }
                        }
                    }

                    BluetoothManager.sendData(BleData.VectorPath(currentChunkJson))
                } else {
                    Log.d("debugSendData", "Plus de chunk à envoyer, fin du chemin atteinte.")
                }
            }
        }
    }

    fun getNextChunkFromFullPath(): List<List<Double>> {
        if (lastSentIndex >= routePoints.size) {
            return emptyList()
        }
        val endIndex = (lastSentIndex + chunkSize).coerceAtMost(routePoints.size)
        val chunk = routePoints.subList(lastSentIndex, endIndex)
        lastSentIndex = endIndex
        return chunk.map { listOf(it.latitude, it.longitude) }
    }

    data class ClosestPointResult(
        val index: Int,
        val point: GeoPoint,
        val speed: Int?
    )

    fun getClosestPointWithSpeed(
        currentPosition: LatLng,
        geoPoints: List<GeoPoint>,
    ): ClosestPointResult {
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            return 2 * R * asin(sqrt(a))
        }

        val index = geoPoints.indices.minByOrNull { i ->
            val pt = geoPoints[i]
            haversine(currentPosition.latitude, currentPosition.longitude, pt.latitude, pt.longitude)
        } ?: 0

        val speed = speedSegments.firstOrNull { index in it.fromIndex until it.toIndex }?.speed

        return ClosestPointResult(index, geoPoints[index], speed)
    }





    /**
     * getCurrentPosition
     * Retrieves the current GPS position.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getCurrentPosition() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                currentPosition = GeoPoint(lat, lon)
                configureMap(GeoPoint(lat, lon))
            } else {
                Toast.makeText(requireContext(), "Impossible d’obtenir la localisation", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * deviceIsConnected
     * Checks if the device is connected.
     */
    private fun deviceIsConnected() {
        if (BluetoothManager.isConnected()) {
            requireActivity().runOnUiThread {
                iconDevice.setImageResource(com.quentin.navigationapp.R.drawable.icon_device_connected)
                tvConnectDevice.text = "Connecté"
            }

        } else {
            requireActivity().runOnUiThread {
                iconDevice.setImageResource(com.quentin.navigationapp.R.drawable.icon_device_disconnected)
                tvConnectDevice.text = "Déconnecté"
            }
        }
    }



    /**
     * getCoordinatesFromAddress
     * Retrieves the GPS coordinates from an address.
     * @param address The address to geocode.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getCoordinatesFromAddress(address: String) {
        val geocoder = Geocoder(requireContext())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocationName(
                address,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(resultList: MutableList<Address>) {
                        requireActivity().runOnUiThread {
                            handleGeocodeResult(resultList)
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "Erreur de géocodification : $errorMessage",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }
    }

    /**
     * handleGeocodeResult
     * Handles the result of the geocoding.
     * @param resultList The list of results.
     * @calls displayAddress
     * @calls getDirections
     */
    private fun handleGeocodeResult(resultList: List<Address>) {
        if (resultList.isNotEmpty()) {
            val loc = resultList[0]
            val destinationLatLng = LatLng(
                loc.latitude.takeIf { !it.isNaN() } ?: 0.0,
                loc.longitude.takeIf { !it.isNaN() } ?: 0.0
            )
            currentDestination = GeoPoint(destinationLatLng.latitude, destinationLatLng.longitude)
            displayAddress(loc.getAddressLine(0).orEmpty())
            getDirections(destinationLatLng)
        } else {
            Toast.makeText(requireContext(), "Adresse non trouvée", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * getDirections
     * Retrieves the directions from the current position to the destination.
     * @param destinationLatLng The GPS coordinates of the destination.
     */
    private fun getDirections(destinationLatLng: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentLatLng = LatLng(
                    currentPosition.latitude,
                    currentPosition.longitude
                )
                val navInstructions = mutableListOf<NavigationInstruction>()
                totalMinutes = 0
                totalKilometers = 0.0

                // Call Graphhopper API
                val response = navigationService.getRoute(currentLatLng, destinationLatLng, vehicle, weightings)
                val ghResponse: Path? = response.paths.firstOrNull()

                if (ghResponse != null) {
                    val coords = ghResponse.points.coordinates

                    if (BluetoothManager.isConnected()) {
                        //BluetoothManager.sendData(BleData.VectorPath(coords))
                    } else {
                        deviceIsConnected()
                    }

                    //time in milliseconds
                    val timeInMillis = ghResponse.time
                    totalMinutes = timeInMillis / 1000 / 60

                    //distance in meters
                    val distanceInMeters = ghResponse.distance
                    totalKilometers = distanceInMeters / 1000

                    speedSegments = ghResponse.details["max_speed"]?.mapNotNull { segment ->
                        val from = (segment.getOrNull(0) as? Number)?.toInt()
                        val to = (segment.getOrNull(1) as? Number)?.toInt()
                        val speed = (segment.getOrNull(2) as? Number)?.toInt() // null possible si la vitesse est inconnue
                        if (from != null && to != null) SpeedSegment(from, to, speed) else null
                    } ?: emptyList()

                    val geoPointsList: List<GeoPoint> = coords.map { (lon, lat) ->
                        GeoPoint(lat, lon)
                    }

                    simplifiedPath = geoPointsList.filterIndexed { index, _ -> index % 2 == 0 }
                    lastSimplifiedPath = simplifiedPath

                    ghResponse.instructions.forEach { instr ->
                        val (startIdx, endIdx) = instr.interval
                        val startGeo = geoPointsList[startIdx]
                        val endGeo = geoPointsList[endIdx]
                        val sign = instr.sign

                        val arrowCode: Int? = (if (sign == 6) {
                            getRoundaboutCodeFromBearing(instr.turn_angle)
                        } else {
                            sign?.toInt()
                        })

                        navInstructions += NavigationInstruction(instr.text, startGeo, arrowCode?: 0)
                        navInstructions += NavigationInstruction(instr.text, endGeo, arrowCode?: 0)
                    }
                    instructions = navInstructions

                    displayVectorPath(geoPointsList)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Erreur de la requête: $e", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * displayVectorPath
     * Displays the vector path on the map.
     * @param coordinates The list of GPS coordinates.
     */
    private suspend fun displayVectorPath(coordinates: List<GeoPoint>) {
        routePoints = coordinates.map { GeoPoint(it.latitude, it.longitude) }

        // Delete old vector track
        currentRoutePolyline?.let { old ->
            mapView.overlays.remove(old)
            mapView.invalidate()
        }
        currentBorderPolyline?.let { old ->
            mapView.overlays.remove(old)
            mapView.invalidate()
        }

        // Create and add new path on the MapView
        val newPolyline = Polyline().apply {
            setPoints(routePoints)
            width = 10F
            color = Color.YELLOW
        }
        val borderPolyline = Polyline().apply {
            setPoints(routePoints)
            width = 13f
            color = Color.BLACK
        }
        mapView.overlays.add(borderPolyline)
        mapView.overlays.add(newPolyline)
        currentRoutePolyline = newPolyline
        currentBorderPolyline = borderPolyline

        if (!isNavigating) {
            val bbox = BoundingBox.fromGeoPointsSafe(routePoints)
            Handler(Looper.getMainLooper()).postDelayed({
                mapView.zoomToBoundingBox(bbox, true, 300)
            }, 300)
        }

        // Add navigation arrow
        mapView.overlays.add(arrowMarker)
        mapView.invalidate()

        updateRemainingNavigation(
            originalDistanceMeters = totalKilometers,
            originalTime = totalMinutes,
        )
    }

    /**
     * routeRecalculation
     * Recalculates the route if the current position is far from the initial route.
     * @calls getDirections
     */
    private fun routeRecalculation() {
        val destLatLng = LatLng(
            currentDestination.latitude,
            currentDestination.longitude
        )
        getDirections(destLatLng)
    }

    /**
     * getRoundaboutIconFromBearing
     * Gets the roundabout icon for the given bearing.
     * @param bearing The bearing.
     * @return The roundabout icon.
     */
    private fun getRoundaboutCodeFromBearing(bearing: Double?): Int {
        val safeBearing = bearing ?: 0.0
        val angleDeg = Math.toDegrees(safeBearing)
        return when (angleDeg) {
            in -70.0..0.0 -> 10
            in -120.0..-70.0 -> 11
            in -170.0..-120.0 -> 12
            in -190.0..-170.0 -> 13
            in -230.0..-190.0 -> 14
            in -280.0..-230.0 -> 15
            in -340.0..-280.0 -> 16
            in -360.0..-340.0 -> 16
            else -> 0
        }
    }


    /**
     * navigationStartView
     * Displays the navigation view.
     */
    private fun navigationStartView() {
        mapView.controller.setZoom(18.0)

        val paramsLayoutControl = layoutControl.layoutParams as ViewGroup.MarginLayoutParams
        paramsLayoutControl.topMargin = (10 * resources.displayMetrics.density).toInt()
        layoutControl.layoutParams = paramsLayoutControl

        val paramsLayoutConnexion = layoutConnexion.layoutParams as ViewGroup.MarginLayoutParams
        paramsLayoutConnexion.topMargin = (10 * resources.displayMetrics.density).toInt()
        layoutConnexion.layoutParams = paramsLayoutConnexion

        btnFinishNavigation.visibility = View.VISIBLE
        layoutNavigationInput.visibility = View.GONE
        btnStartNavigation.visibility = View.GONE
    }

    /**
     * navigationStopView
     * Hides the navigation view.
     */
    private fun navigationStopView() {
        val paramsLayoutControl = layoutControl.layoutParams as ViewGroup.MarginLayoutParams
        paramsLayoutControl.topMargin = (80 * resources.displayMetrics.density).toInt()
        layoutControl.layoutParams = paramsLayoutControl

        val paramsLayoutConnexion = layoutConnexion.layoutParams as ViewGroup.MarginLayoutParams
        paramsLayoutConnexion.topMargin = (80 * resources.displayMetrics.density).toInt()
        layoutConnexion.layoutParams = paramsLayoutConnexion

        btnFinishNavigation.visibility = View.GONE
        layoutNavigationInput.visibility = View.VISIBLE
        btnStartNavigation.visibility = View.VISIBLE
    }

    /**
     * navigationisStarted
     * Sets the navigation state.
     * @param value The navigation state.
     */
    private fun navigationisStarted(value: Boolean) {
        isNavigating = value
    }

    /**
     * updateArrowOverlay
     * Updates the arrow overlay.
     * @param position The current GPS position.
     * @calls animateArrowTo
     * @calls animateMapTo
     * @calls checkInstructionTrigger
     */
    private suspend fun updateArrowOverlay(position: Location) {

        val currentGeo = GeoPoint(position.latitude, position.longitude)
        val bearing: Float = position.bearing

        arrowMarker.apply {
            icon = ContextCompat.getDrawable(requireContext(), arrowIcon)
        }
        animateArrowTo(currentGeo)
        animateMapTo(currentGeo, bearing)
        checkInstructionTrigger(currentGeo, bearing)
        lastPosition = position
        hideLoadingDialog()
    }

    /**
     * animateArrowTo
     * Animates the arrow to the given position.
     * @param newPos The new GPS position.
     * @param duration The animation duration.
     */
    private fun animateArrowTo(newPos: GeoPoint, duration: Long = 1500L) {
        val oldCenter = lastCenter ?: newPos
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { anim ->
                val frac = anim.animatedFraction
                val lat = oldCenter.latitude + (newPos.latitude - oldCenter.latitude) * frac
                val lon = oldCenter.longitude + (newPos.longitude - oldCenter.longitude) * frac
                val interp = GeoPoint(lat, lon)
                arrowMarker.position = interp
                arrowMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.invalidate()
            }
        }
        animator.start()
    }

    /**
     * animateMapTo
     * Animates the map to the given position and bearing.
     * @param newPos The new GPS position.
     * @param newBearing The new bearing.
     * @param duration The animation duration.
     */
    private fun animateMapTo(newPos: GeoPoint, newBearing: Float, duration: Long = 1300L) {
        val oldCenter = lastCenter ?: newPos
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { anim ->
                val frac = anim.animatedFraction
                val lat = oldCenter.latitude + (newPos.latitude - oldCenter.latitude) * frac
                val lon = oldCenter.longitude + (newPos.longitude - oldCenter.longitude) * frac
                val interp = GeoPoint(lat, lon)
                mapView.mapOrientation = -newBearing
                lastCenter = interp
                mapView.controller.setCenter(interp)
                mapView.invalidate()
            }
        }
        animator.start()
    }

    /**
     * checkInstructionTrigger
     * Checks if an instruction should be displayed.
     * @param currentLocation The current GPS position.
     * @param deviceHeading The current device heading.
     * @calls showInstruction
     * @calls normalizeBearingDiff
     */
    private suspend fun checkInstructionTrigger(currentLocation: GeoPoint, deviceHeading: Float) {
        if (instructions.isEmpty()) return

        val announceDistance = 80.0 //Announced distance in meters
        val minDistanceToFire = 40.0 //Minimum distance to fire in meters
        val maxAngleDiff = 90.0 //Maximum angle difference in degrees

        //Step One: Filter the upcoming instructions
        val upcoming = instructions
            .mapIndexed { idx, instr -> idx to instr }
            .filter { (idx, _) -> idx > lastDisplayedInstructionIndex }
            .filter { (_, instr) ->
                val bearingToInstr = currentLocation
                    .toLocation()
                    .bearingTo(instr.location.toLocation()).toDouble()
                abs(normalizeBearingDiff(bearingToInstr - deviceHeading)) < maxAngleDiff
            }

        //Second step: Show the instruction
        if (upcoming.isEmpty()) {
            if (BluetoothManager.isConnected()) {
                if (0 != lastDirection) {
                    BluetoothManager.sendData(BleData.Direction(0))
                    lastDirection = 0
                }
            } else {
                deviceIsConnected()
            }
            return
        }

        val (closestIdx, closestInstr) = upcoming.minByOrNull { (_, instr) ->
            currentLocation.distanceToAsDouble(instr.location)
        }!!
        val distance = currentLocation.distanceToAsDouble(closestInstr.location)

        //Third step: display then next instruction
        when {
            //pre-announcement:
            distance < announceDistance && closestIdx == lastDisplayedInstructionIndex + 1 -> {
                showInstruction(closestInstr)
            }
            //action performed:
            distance < minDistanceToFire -> {
                lastDisplayedInstructionIndex = closestIdx
                if (lastDisplayedInstructionIndex + 1 < instructions.size) {
                    showInstruction(instructions[lastDisplayedInstructionIndex + 1])
                }
            }
            else -> {
                if (lastDirection != 0) {
                    BluetoothManager.sendData(BleData.Direction(0))
                    lastDirection = 0
                }
                delay(500)
                if (distance.toInt() != lastDistanceBeforeDirection?.toInt()) {
                    BluetoothManager.sendData(BleData.DistanceBeforeDirection(distance.toInt().toString()))

                    lastDistanceBeforeDirection = distance

                    timeAndDistanceRemaining()
                }
            }
        }
    }

    suspend fun timeAndDistanceRemaining() {

        val routeLatLng = routePoints.map { LatLng(it.latitude, it.longitude) }
        val distanceTraveledMeters = calculateDistanceTraveled(
            LatLng(currentPosition.latitude, currentPosition.longitude),
            routeLatLng
        )

        val totalDistanceMeters = totalKilometers * 1000 // Convertir en mètres

        val distanceRemainingMeters = totalDistanceMeters - distanceTraveledMeters
        val totalTimeMillis = totalMinutes * 60 * 1000L // Convertir minutes en millisecondes

        val timeTraveledMillis = if (totalDistanceMeters > 0)
            (distanceTraveledMeters / totalDistanceMeters) * totalTimeMillis
        else 0.0

        val timeRemainingMillis = totalTimeMillis - timeTraveledMillis

        val minutesRemaining = (timeRemainingMillis / 1000 / 60).toInt()
        val kilometersRemaining = (distanceRemainingMeters / 1000).toInt()

        updateRemainingNavigation(kilometersRemaining.toDouble(), minutesRemaining.toLong())
    }

    fun calculateDistanceTraveled(currentPosition: LatLng, routePoints: List<LatLng>): Double {
        if (routePoints.isEmpty()) return 0.0

        // Étape 1 : trouver le point du tracé le plus proche de la position actuelle
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE
        for ((index, point) in routePoints.withIndex()) {
            val dist = haversine(currentPosition.latitude, currentPosition.longitude, point.latitude, point.longitude)
            if (dist < minDistance) {
                minDistance = dist
                closestIndex = index
            }
        }

        // Étape 2 : sommer toutes les distances du début du tracé jusqu'à ce point
        var distanceTraveled = 0.0
        for (i in 0 until closestIndex) {
            val from = routePoints[i]
            val to = routePoints[i + 1]
            distanceTraveled += haversine(from.latitude, from.longitude, to.latitude, to.longitude)
        }

        return distanceTraveled // En mètres
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Rayon de la Terre en mètres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * updateRemainingNavigation
     * Updates the remaining navigation.
     * @param originalDistanceMeters The original distance in meters.
     * @param originalTime The original time in milliseconds.
     */
    private suspend fun updateRemainingNavigation(
        originalDistanceMeters: Double,
        originalTime: Long,
    ) {
        val time = originalTime
        // Time:
        val hours = originalTime / 60
        val minutes = originalTime % 60

        val timeFormatted = when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min"
            else -> "<1 min"
        }

        //Distance:
        val originalDistanceMeters = String.format("%.0f", originalDistanceMeters).toString()

        if (lastKmRemaining != originalDistanceMeters.toInt()) {
            lastKmRemaining = originalDistanceMeters.toInt()
            requireActivity().runOnUiThread {
                navigationDistance.text = "${originalDistanceMeters} km"
            }

            if (BluetoothManager.isConnected()) {
                Log.d("debugSendData", "DistanceMetersBeforeInstruction = $originalDistanceMeters")
                BluetoothManager.sendData(BleData.KilometersRemaining(originalDistanceMeters.toInt()))
            } else {
                deviceIsConnected()
            }
        }

        if (lastMinRemaining != time.toInt()) {
            lastMinRemaining = time.toInt()

            requireActivity().runOnUiThread {
                navigationTime.text = timeFormatted.toString()
            }

            if (BluetoothManager.isConnected()) {
                delay(500)
                BluetoothManager.sendData(BleData.TimeRemaining(time.toInt()))
            } else {

                deviceIsConnected()
            }

        }
    }

    /**
     * GeoPoint.toLocation
     * Converts a GeoPoint to a Location.
     * @return The converted Location.
     */
    private fun GeoPoint.toLocation(): Location =
        Location("osmdroid").apply {
            latitude = this@toLocation.latitude
            longitude = this@toLocation.longitude
        }

    /**
     * showInstruction
     * Displays the given instruction.
     * @param instr The instruction to display.KilometersRemaining
     */
    private fun showInstruction(instr: NavigationInstruction) {

        if (BluetoothManager.isConnected()) {
            if (instr.arrow.toInt() != lastDirection) {
                BluetoothManager.sendData(BleData.Direction(instr.arrow.toInt()))
                lastDirection = instr.arrow.toInt()
            }
        } else {
            deviceIsConnected()
        }

        lifecycleScope.launch {
            delay(2000L) // Wait 2 seconds
        }
    }

    /**
     * distanceToSegment
     * Calculates the distance from the current position to the segment.
     * @param current The current position.
     * @param start The start of the segment.
     * @param end The end of the segment.
     * @return The distance from the current position to the segment.
     */
    private fun distanceToSegment(current: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val R = 6_371_000.0
        val lat0 = Math.toRadians(current.latitude)
        val lon0 = Math.toRadians(current.longitude)
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)
        val meanLat = (lat1 + lat2) / 2
        val cosMeanLat = cos(meanLat)
        val x0 = lon0 * cosMeanLat * R
        val y0 = lat0 * R
        val x1 = lon1 * cosMeanLat * R
        val y1 = lat1 * R
        val x2 = lon2 * cosMeanLat * R
        val y2 = lat2 * R
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) return current.distanceToAsDouble(start)
        val t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy)
        val tClamped = min(1.0, max(0.0, t))
        val projX = x1 + tClamped * dx
        val projY = y1 + tClamped * dy
        val distX = x0 - projX
        val distY = y0 - projY
        return hypot(distX, distY)
    }

    /**
     * isFarFromRoute
     * Checks if the current position is far from the route.
     * @param current The current position.
     * @param routePoints The list of route points.
     * @return True if the current position is far from the route, false otherwise.
     * @calls distanceToSegment
     */
    private fun isFarFromRoute(current: GeoPoint, routePoints: List<GeoPoint>): Boolean {
        val maxDistanceMeters = 30.0
        if (routePoints.size < 2) return true
        val minDist = routePoints.zipWithNext().minOfOrNull { (start, end) ->
            distanceToSegment(current, start, end)
        } ?: Double.MAX_VALUE
        return minDist > maxDistanceMeters
    }

    /**
     * displayAddress
     * Displays the given address.
     * @param address The address to display.
     */
    private fun displayAddress(address: String) {
        if (address.isNotEmpty()) {
            tvDestination.text = "📍 $address"
        }
    }

    /**
     * configureMap
     * Configures the map.
     * @param location The GPS position.
     * @calls displayArrowNavigation
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
     * Displays the arrow navigation.
     * @param location The GPS position.
     */
    private fun displayArrowNavigation(location: GeoPoint) {
        if (::mapView.isInitialized && ::arrowMarker.isInitialized) {
            mapView.overlays.remove(arrowMarker)
            mapView.invalidate()
        }
        arrowMarker = Marker(mapView).apply {
            icon = ContextCompat.getDrawable(requireContext(), com.quentin.navigationapp.R.drawable.ic_dafault_arrow)
            setAnchor(Marker.ANCHOR_TOP, Marker.ANCHOR_TOP)
        }
        mapView.overlays.add(arrowMarker)
        arrowMarker.position = location
        mapView.controller.setCenter(location)
        mapView.invalidate()
    }

    /**
     * normalizeBearingDiff
     * Normalizes the bearing difference.
     * @param diff The bearing difference.
     * @return The normalized bearing difference.
     */
    private fun normalizeBearingDiff(diff: Double): Double =
        ((diff + 540) % 360) - 180

    /**
     * requestPermissionsIfNeeded
     * Requests permissions if needed.
     * @param onGranted The callback to call when permissions are granted.
     * @calls registerForActivityResult
     */
    private fun requestPermissionsIfNeeded(onGranted: () -> Unit) {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) onGranted()
        }
        if (perms.any { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }) {
            launcher.launch(perms)
        } else {
            onGranted()
        }
    }
}


