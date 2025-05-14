package com.quentin.navigationapp.data

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.quentin.navigationapp.model.DirectionsResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.quentin.navigationapp.network.OpenRouteServiceAPI
import com.quentin.navigationapp.BuildConfig
import com.quentin.navigationapp.network.DirectionsRequest
import com.quentin.navigationapp.network.GraphHopperResponse
import okhttp3.Response

class NavigationService {

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.openrouteservice.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(OpenRouteServiceAPI::class.java)

    suspend fun getDirections(start: LatLng, end: LatLng): DirectionsResponse {

        val request = DirectionsRequest(
            coordinates = listOf(
                listOf(start.longitude, start.latitude),
                listOf(end.longitude, end.latitude)
            )
        )
        return api.getDirections("Bearer ${BuildConfig.ORS_API_KEY}", request)
    }

    // Graphhopper:

    val retrofitGraphhopper = Retrofit.Builder()
        .baseUrl("https://graphhopper.com/api/1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiGraphhopper = retrofitGraphhopper.create(OpenRouteServiceAPI::class.java)

    suspend fun getRoute(start: LatLng, end: LatLng): GraphHopperResponse {

        Log.d("OtherAPI", "Dans getRoute")

        val point1 = "${start.latitude}, ${start.longitude}"
        val point2 = "${end.latitude}, ${end.longitude}"

        Log.d("OtherAPI", "Envoyer: $point1, $point2,\"car\", \"fr\", true, true,")

        return apiGraphhopper.getRoute(point1, point2, "car", "fr", true, true, false, BuildConfig.GH_API_KEY)

    }
}



