package com.quentin.navigationapp.data

import com.google.android.gms.maps.model.LatLng
import com.quentin.navigationapp.model.DirectionsResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.quentin.navigationapp.network.OpenRouteServiceAPI
import com.quentin.navigationapp.BuildConfig
import com.quentin.navigationapp.network.DirectionsRequest

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
}
