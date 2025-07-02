package com.quentin.navigationapp.data

import com.google.android.gms.maps.model.LatLng
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.quentin.navigationapp.network.OpenRouteServiceAPI
import com.quentin.navigationapp.BuildConfig
import com.quentin.navigationapp.network.GraphHopperResponse

class NavigationService {

    val retrofitGraphhopper = Retrofit.Builder()
        .baseUrl("https://graphhopper.com/api/1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiGraphhopper = retrofitGraphhopper.create(OpenRouteServiceAPI::class.java)

    suspend fun getRoute(start: LatLng, end: LatLng, vehicle: String, weighting: String): GraphHopperResponse {
        val point1 = "${start.latitude}, ${start.longitude}"
        val point2 = "${end.latitude}, ${end.longitude}"

        return apiGraphhopper.getRoute(point1, point2, vehicle, "fr", weighting,true, true, false, listOf("max_speed"), BuildConfig.GH_API_KEY)
    }
}



