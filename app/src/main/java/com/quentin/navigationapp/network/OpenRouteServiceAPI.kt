package com.quentin.navigationapp.network

import com.quentin.navigationapp.model.DirectionsResponse
import okhttp3.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenRouteServiceAPI {

    @POST("v2/directions/driving-car/geojson")
    suspend fun getDirections(
        @Header("Authorization") apiKey: String,
        @Body request: DirectionsRequest,
    ): DirectionsResponse


    @GET("route")
    suspend fun getRoute(
        @Query("point") point1: String,
        @Query("point") point2: String,
        @Query("vehicle") vehicle: String,
        @Query("weighting") weighting: String,
        @Query("locale") locale: String = "fr",
        @Query("instructions") instructions: Boolean = true,
        @Query("calc_points") calcPoints: Boolean = true,
        @Query("points_encoded") pointsEncoded: Boolean = false,
        @Query("key") apiKey: String
    ): GraphHopperResponse
}
