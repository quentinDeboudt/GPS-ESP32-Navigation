package com.quentin.navigationapp.network

import retrofit2.http.GET
import retrofit2.http.Query

interface OpenRouteServiceAPI {

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
