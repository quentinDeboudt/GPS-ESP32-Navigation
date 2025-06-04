package com.quentin.navigationapp

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import com.quentin.navigationapp.data.NavigationService
import com.quentin.navigationapp.network.Path
import java.io.IOException
import java.util.Locale
import kotlin.math.*

data class NavigationInstruction(
    val message: String,
    val location: GeoPoint,
    val arrow: Drawable? = null
)

class MapFragment : Fragment() {

    // Widgets du layout
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
    private lateinit var btnOpenModal: Button
    private lateinit var speedSpinner: Spinner
    private lateinit var searchNavigationButton: Button

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
    private var currentProfile: Profile? = null

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val PREFS_NAME = "app_navigation_prefs"
    private val KEY_PROFILES_JSON = "profiles_json"
    private val KEY_CURRENT_PROFILE = "current_profile"

    data class Profile(
        val name: String,
        val type: String,
        val subType: VehicleSubType,
        val consumption: Double
    )

    data class VehicleSubType(
        val label: String,
        val routingType: String
    ){
        override fun toString(): String = label
    }

    /**
     * loadProfilesFromPrefs
     * Load the profiles from the shared preferences.
     * @param context The context of the activity.
     * @return A list of profiles.
     */
    private fun loadProfilesFromPrefs(context: Context): List<Profile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        val jsonArray = org.json.JSONArray(jsonString)
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
                .setMinUpdateIntervalMillis(500L) // 0,5 seconde
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
        // On gonfle le layout complet de la page 2
        return inflater.inflate(R.layout.map_page, container, false)
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
                "Profil actuel : ${currentProfile!!.name} (${currentProfile!!.type} - ${currentProfile!!.subType})",
                Toast.LENGTH_LONG
            ).show()

            vehicle = currentProfile!!.subType.routingType

        } else {
            Toast.makeText(requireContext(), "Aucun profil actif détecté", Toast.LENGTH_SHORT).show()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager

        mapView = view.findViewById(R.id.map)
        tvInstruction = view.findViewById(R.id.tvInstruction)
        arrowImageView = view.findViewById(R.id.arrowImageView)
        tvDestination = view.findViewById(R.id.etDestination)
        layoutInstruction = view.findViewById(R.id.layout_instruction)
        layoutControl = view.findViewById(R.id.layout_control)
        navigationTime = view.findViewById(R.id.navigation_time)
        navigationDistance = view.findViewById(R.id.navigation_distance)
        btnStartNavigation = view.findViewById(R.id.btnStartNavigation)
        layoutNavigationInput = view.findViewById(R.id.layout_navigation_Input)
        btnFinishNavigation = view.findViewById(R.id.btnFinishNavigation)
        btnOpenModal = view.findViewById(R.id.btnOpenModal)
        searchNavigationButton = view.findViewById(R.id.searchNavigationButton)

        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.GONE
        layoutInstruction.visibility = View.GONE
        layoutControl.visibility = View.GONE

        getCurrentPosition()

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
                navigationStartView()
                navigationisStarted(true)

                navigationJob = lifecycleScope.launch {
                    locationFlow.collect { location ->
                        currentPosition = GeoPoint(location.latitude, location.longitude)
                        updateArrowOverlay(location)

                        if (isFarFromRoute(currentPosition, routePoints, maxDistanceMeters = 100.0)) {
                            routeRecalculation()
                        }
                        displayLastPathPolyline()
                        updateRemainingNavigation(
                            currentPoint = currentPosition,
                            plannedRoutePoints = routePoints,
                            originalDistanceMeters = totalKilometers,
                            originalTimeMs = totalMinutes,
                            tvDistance = navigationDistance,
                            tvTime = navigationTime
                        )
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

        // open dialog connection wifi/bluetooth
        btnOpenModal.setOnClickListener {
            val dialog = DeviceConnectDialogFragment()
            dialog.show(parentFragmentManager, "DeviceConnectDialog")
        }
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
     * getCoordinatesFromAddress
     * Retrieves the GPS coordinates from an address.
     * @param address The address to geocode.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getCoordinatesFromAddress(address: String) {
        val geocoder = Geocoder(requireContext())
        try {
            val resultList = geocoder.getFromLocationName(address, 1) ?: emptyList()
            if (resultList.isNotEmpty()) {
                val loc = resultList[0]
                val destinationLatLng = com.google.android.gms.maps.model.LatLng(
                    loc.latitude.takeIf { !it.isNaN() } ?: 0.0,
                    loc.longitude.takeIf { !it.isNaN() } ?: 0.0
                )
                currentDestination = GeoPoint(destinationLatLng.latitude, destinationLatLng.longitude)
                displayAddress(loc.getAddressLine(0).orEmpty())
                getDirections(destinationLatLng)
            } else {
                Toast.makeText(requireContext(), "Adresse non trouvée", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Erreur de géocodification", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * getDirections
     * Retrieves the directions from the current position to the destination.
     * @param destinationLatLng The GPS coordinates of the destination.
     */
    private fun getDirections(destinationLatLng: com.google.android.gms.maps.model.LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentLatLng = com.google.android.gms.maps.model.LatLng(
                    currentPosition.latitude,
                    currentPosition.longitude
                )
                val navInstructions = mutableListOf<NavigationInstruction>()
                totalMinutes = 0
                totalKilometers = 0.0

                // Appel à l'API via NavigationService
                val response = navigationService.getRoute(currentLatLng, destinationLatLng, vehicle, weightings)
                val ghResponse: Path? = response.paths.firstOrNull()

                if (ghResponse != null) {
                    val coords = ghResponse.points.coordinates
                    totalMinutes = ghResponse.time
                    totalKilometers = ghResponse.distance / 1000.0

                    val geoPointsList: List<GeoPoint> = coords.map { (lon, lat) ->
                        GeoPoint(lat, lon)
                    }

                    // Construction des instructions détaillées
                    ghResponse.instructions.forEach { instr ->
                        val (startIdx, endIdx) = instr.interval
                        val startGeo = geoPointsList[startIdx]
                        val endGeo = geoPointsList[endIdx]
                        val sign = instr.sign

                        val arrowDrawable: Drawable? = if (sign == 6) {
                            getRoundaboutIconFromBearing(instr.turn_angle)
                        } else {
                            getArrowForInstruction(sign)
                        }

                        navInstructions += NavigationInstruction(instr.text, startGeo, arrowDrawable)
                        navInstructions += NavigationInstruction(instr.text, endGeo, arrowDrawable)
                    }
                    instructions = navInstructions

                    displayVectorPath(geoPointsList)
                }
            } catch (e: Exception) {
                Log.e("API", "Erreur: $e")
            }
        }
    }

    /**
     * displayVectorPath
     * Displays the vector path on the map.
     * @param coordinates The list of GPS coordinates.
     */
    private fun displayVectorPath(coordinates: List<GeoPoint>) {
        routePoints = coordinates.map { GeoPoint(it.latitude, it.longitude) }

        // Delete old vector track
        currentRoutePolyline?.let { old ->
            mapView.overlays.remove(old)
            mapView.invalidate()
        }

        // Create and add new path on the MapView
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
            }, 300)
        }

        // Add navigation arrow
        mapView.overlays.add(arrowMarker)
        mapView.invalidate()
    }

    /**
     * routeRecalculation
     * Recalculates the route if the current position is far from the initial route.
     * @calls getDirections
     */
    private fun routeRecalculation() {
        val destLatLng = com.google.android.gms.maps.model.LatLng(
            currentDestination.latitude,
            currentDestination.longitude
        )
        getDirections(destLatLng)
    }

    /**
     * displayLastPathPolyline
     * Displays the last path on the map.
     */
    private fun displayLastPathPolyline() {
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

    /**
     * formatTime
     * Formats the time in milliseconds to a string.
     */
    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d:%02d", hours, remainingMinutes, remainingSeconds)
    }

    /**
     * getArrowForInstruction
     * Gets the arrow icon for the given instruction.
     * @param sign The instruction sign.
     * @return The arrow icon.
     */
    private fun getArrowForInstruction(sign: Int?): Drawable? {
        return when (sign) {
            0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_straight_bk)
            -3, -2 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_left_2_bk)
            -1 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_left_1_bk)
            3, 2 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_right_2_bk)
            1 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_right_1_bk)
            7 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_left_1_bk)
            8 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_right_1_bk)
            else -> null
        }
    }

    /**
     * getRoundaboutIconFromBearing
     * Gets the roundabout icon for the given bearing.
     * @param bearing The bearing.
     * @return The roundabout icon.
     */
    private fun getRoundaboutIconFromBearing(bearing: Double?): Drawable? {
        val safeBearing = bearing ?: 0.0
        val angleDeg = Math.toDegrees(safeBearing)
        return when (angleDeg) {
            in -70.0..0.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r1_bk)
            in -120.0..-70.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r2_bk)
            in -170.0..-120.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r3_bk)
            in -190.0..-170.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r4_bk)
            in -230.0..-190.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r5_bk)
            in -280.0..-230.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r6_bk)
            in -340.0..-280.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r7_bk)
            in -360.0..-340.0 -> ContextCompat.getDrawable(requireContext(), R.drawable.nav_roundabout_r8_bk)
            else -> null
        }
    }

    /**
     * navigationStartView
     * Displays the navigation view.
     */
    private fun navigationStartView() {
        // Ajuster le layout de la carte en haut pour laisser place aux instructions
        val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = 0
        mapView.layoutParams = params
        mapView.controller.setZoom(18.0)

        val instrParams = layoutInstruction.layoutParams as ViewGroup.MarginLayoutParams
        instrParams.topMargin = (15 * resources.displayMetrics.density).toInt()
        layoutInstruction.layoutParams = instrParams

        layoutInstruction.visibility = View.VISIBLE
        layoutNavigationInput.visibility = View.GONE
        btnStartNavigation.visibility = View.GONE
        btnFinishNavigation.visibility = View.VISIBLE
    }

    /**
     * navigationStopView
     * Hides the navigation view.
     */
    private fun navigationStopView() {
        val params = mapView.layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = (200 * resources.displayMetrics.density).toInt() // ~150dp
        mapView.layoutParams = params

        btnFinishNavigation.visibility = View.GONE
        layoutNavigationInput.visibility = View.VISIBLE
        layoutInstruction.visibility = View.GONE
        btnStartNavigation.visibility = View.VISIBLE
        layoutFilter.visibility = View.VISIBLE
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
    private fun updateArrowOverlay(position: Location) {
        val currentGeo = GeoPoint(position.latitude, position.longitude)
        val bearing: Float = position.bearing

        arrowMarker.apply {
            icon = ContextCompat.getDrawable(requireContext(), arrowIcon)
        }
        animateArrowTo(currentGeo)
        animateMapTo(currentGeo, bearing)
        checkInstructionTrigger(currentGeo, bearing)
        lastPosition = position
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
    private fun checkInstructionTrigger(currentLocation: GeoPoint, deviceHeading: Float) {
        if (instructions.isEmpty()) return

        val announceDistance = 80.0
        val minDistanceToFire = 20.0
        val maxAngleDiff = 90.0

        // Find upcoming instructions
        val upcoming = instructions
            .mapIndexed { idx, instr -> idx to instr }
            .filter { (idx, _) -> idx > lastDisplayedInstructionIndex }
            .filter { (_, instr) ->
                val bearingToInstr = currentLocation
                    .toLocation()
                    .bearingTo(instr.location.toLocation()).toDouble()
                abs(normalizeBearingDiff(bearingToInstr - deviceHeading)) < maxAngleDiff
            }

        // Display instruction (straight destination)
        if (upcoming.isEmpty()) {
            tvInstruction.text = ""
            arrowImageView.setImageDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.nav_straight_bk)
            )
            return
        }

        val (closestIdx, closestInstr) = upcoming.minByOrNull { (_, instr) ->
            currentLocation.distanceToAsDouble(instr.location)
        }!!

        val distance = currentLocation.distanceToAsDouble(closestInstr.location)

        when {
            distance < announceDistance && closestIdx == lastDisplayedInstructionIndex + 1 -> {
                showInstruction(closestInstr)
            }
            distance < minDistanceToFire -> {
                lastDisplayedInstructionIndex = closestIdx
                if (lastDisplayedInstructionIndex + 1 < instructions.size) {
                    showInstruction(instructions[lastDisplayedInstructionIndex + 1])
                }
            }
            else -> {
                val displayDist = if (distance >= 1000.0) {
                    String.format("%.1f km", distance / 1000.0)
                } else {
                    String.format("%.0f m", distance)
                }
                tvInstruction.text = displayDist
                arrowImageView.setImageDrawable(
                    ContextCompat.getDrawable(requireContext(), R.drawable.nav_straight_bk)
                )
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
     * @param instr The instruction to display.
     */
    private fun showInstruction(instr: NavigationInstruction) {
        arrowImageView.setImageDrawable(instr.arrow)
        tvInstruction.text = instr.message
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
     * @param maxDistanceMeters The maximum distance from the route.
     * @return True if the current position is far from the route, false otherwise.
     * @calls distanceToSegment
     */
    private fun isFarFromRoute(current: GeoPoint, routePoints: List<GeoPoint>, maxDistanceMeters: Double = 30.0): Boolean {
        if (routePoints.size < 2) return true
        val minDist = routePoints.zipWithNext().minOfOrNull { (start, end) ->
            distanceToSegment(current, start, end)
        } ?: Double.MAX_VALUE
        return minDist > maxDistanceMeters
    }

    /**
     * updateRemainingNavigation
     * Updates the remaining navigation.
     * @param currentPoint The current GPS position.
     * @param plannedRoutePoints The list of planned route points.
     * @param originalDistanceMeters The original distance in meters.
     * @param originalTimeMs The original time in milliseconds.
     * @param tvDistance The distance text view.
     * @param tvTime The time text view.
     */
    private fun updateRemainingNavigation(
        currentPoint: GeoPoint,
        plannedRoutePoints: List<GeoPoint>,
        originalDistanceMeters: Double,
        originalTimeMs: Long,
        tvDistance: TextView,
        tvTime: TextView
    ) {
        if (plannedRoutePoints.size < 2 || originalDistanceMeters <= 0) return

        val closestIndex = plannedRoutePoints
            .mapIndexed { idx, pt -> idx to pt }
            .minByOrNull { it.second.distanceToAsDouble(currentPoint) }
            ?.first ?: return

        var remainingDistance = 0.0
        for (i in closestIndex until plannedRoutePoints.size - 1) {
            remainingDistance += plannedRoutePoints[i].distanceToAsDouble(plannedRoutePoints[i + 1])
        }

        val ratio = remainingDistance / originalDistanceMeters
        val remainingTimeMs = (originalTimeMs * ratio).toLong()
        val km = remainingDistance / 1000.0
        tvDistance.text = String.format(Locale.FRANCE, "%.1f km", km)
        // TODO : afficher le temps restant (format hh:mm ou mm:ss)
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
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_dafault_arrow)
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
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
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
