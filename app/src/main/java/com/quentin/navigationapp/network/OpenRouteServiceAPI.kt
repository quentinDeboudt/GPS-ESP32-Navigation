package com.quentin.navigationapp.network

import com.quentin.navigationapp.model.DirectionsResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenRouteServiceAPI {

    @POST("v2/directions/driving-car/geojson")
    suspend fun getDirections(
        @Header("Authorization") apiKey: String,
        @Body request: DirectionsRequest,
    ): DirectionsResponse

}
