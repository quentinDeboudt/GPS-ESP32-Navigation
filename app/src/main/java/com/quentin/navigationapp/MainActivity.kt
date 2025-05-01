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

class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val navigationService = NavigationService()
    private lateinit var currentLatLng: LatLng
    private lateinit var currentAdress: List<Address>
    private lateinit var finishAddress: List<Address>
    private var currentLocation: GeoPoint? = null
    private var currentBearing: Float = 0f



    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("debug", "onCreate() appelé")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Instruction direction
        val rvSteps = findViewById<RecyclerView>(R.id.rvSteps)
        rvSteps.layoutManager = LinearLayoutManager(this)


        // Initialise fusedLocationClient en tout premier
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

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

    @SuppressLint("MissingPermission") // Géré plus bas
    fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 1000            // 1 seconde
            fastestInterval = 500
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                val location = p0.lastLocation ?: return
                val bearing = location.bearing
                updateCurrentLocation(location.latitude, location.longitude, bearing)
            }
        }


        // Vérifie les permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } else {
            // Sinon, demander la permission à l'utilisateur
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
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
                    tvPosition.text = "🛰️ $currentPosition"
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
     * displayMap - Deplay map with path navigation.
     */
    private fun displayMap(response: DirectionsResponse) {
        val coords: List<GeoPoint> = response.features
            .firstOrNull()?.geometry?.coordinates
            ?.map { GeoPoint(it[1], it[0]) } ?: emptyList()

        val customMapView = object : View(this) {

            //tracé vectoriel:
            val pathPaint = Paint().apply {
                color = Color.WHITE
                strokeWidth = 12f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            //Arriere plan du GPS
            val bgPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawPaint(bgPaint)

                if (coords.size < 2) return

                val projected = projectCoordsToScreen(coords, width, height)

                val path = Path().apply {
                    moveTo(projected[0].x, projected[0].y)
                    for (i in 1 until projected.size) {
                        lineTo(projected[i].x, projected[i].y)
                    }
                }

                // On fait tourner la carte (et donc le tracé)
                canvas.withRotation(-currentBearing, width / 2f, height / 2f) {
                    drawPath(path, pathPaint)
                }

                // Dessiner la flèche au centre de l'écran
                val centerX = width / 2f
                val centerY = height / 2f
                drawArrow(canvas, centerX, centerY)


                //Ajouter des point de couleur (Départ/Arrivée)
                //val startPoint = projected.first()
                val endPoint = projected.last()

               /* val startPointPaint = Paint().apply {
                    color = Color.BLUE
                    style = Paint.Style.FILL
                }

                */
                val finalPointPaint = Paint().apply {
                    color = Color.RED
                    style = Paint.Style.FILL
                }

                //canvas.drawCircle(startPoint.x, startPoint.y, 10f, startPointPaint)
                canvas.drawCircle(endPoint.x, endPoint.y, 10f, finalPointPaint)
            }

            fun drawArrow(canvas: Canvas, centerX: Float, centerY: Float) {
                // Créer un Paint pour la flèche (blanche)
                val arrowPaint = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }

                // Créer un Paint pour la bordure noire de la flèche
                val borderPaint = Paint().apply {
                    color = Color.BLACK
                    strokeWidth = 6f  // Largeur de la bordure
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }

                // Créer un Path pour la flèche (forme triangulaire)
                val path = Path()
                val arrowHeight = 80f  // Longueur de la flèche
                val arrowWidth = 40f   // Largeur de la flèche

                // Définir la forme triangulaire de la flèche (pointe vers le haut)
                path.moveTo(centerX, centerY - arrowHeight)  // Point de départ (haut de la flèche)
                path.lineTo(centerX - arrowWidth, centerY)   // Bas gauche
                path.lineTo(centerX + arrowWidth, centerY)   // Bas droite
                path.close()  // Fermer le triangle

                // Dessiner la flèche (blanche)
                canvas.drawPath(path, arrowPaint)

                // Dessiner la bordure noire
                canvas.drawPath(path, borderPaint)
            }

            fun projectCoordsToScreen(
                geoPoints: List<GeoPoint>,
                width: Int,
                height: Int
            ): List<PointF> {
                val centerLat = currentLocation?.latitude ?: geoPoints.map { it.latitude }.average()
                val centerLon = currentLocation?.longitude ?: geoPoints.map { it.longitude }.average()
                val scale = 400000.0 // Zoom x4
                val centerX = width / 2f
                val centerY = height / 2f

                return geoPoints.map {
                    val dx = (it.longitude - centerLon) * scale
                    val dy = (it.latitude - centerLat) * -scale
                    PointF(centerX + dx.toFloat(), centerY + dy.toFloat())
                }
            }
        }

        val mapContainer = findViewById<FrameLayout>(R.id.mapContainer)
        mapContainer.removeAllViews() // supprime les anciennes vues
        mapContainer.addView(customMapView)
    }

    fun updateCurrentLocation(lat: Double, lon: Double, bearing: Float) {
        this.currentLocation = GeoPoint(lat, lon)
        this.currentBearing = bearing

        val mapContainer = findViewById<FrameLayout>(R.id.mapContainer)
        val customView = mapContainer.getChildAt(0)

        customView?.invalidate() // redessine la vue
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

