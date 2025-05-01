package com.quentin.navigationapp.model

data class DirectionsResponse(
    val type: String,
    val features: List<Feature>,
    val metadata: Metadata
)

data class Feature(
    val type: String,
    val properties: Properties,
    val geometry: Geometry
)

data class Geometry(
    val coordinates: List<List<Double>>,
    val type: String
)


data class Properties(
    val segments: List<Segment>
)

data class Segment(
    val steps: List<Step>
)

data class Step(
    val instruction: String,
    val distance: Double,
    val duration: Double,
    val name: String?,
    val type: Int,
    val way_points: List<Int>,
    val exit_number: Int? = null
)

data class Metadata(
    val query: Query
)

data class Query(
    val coordinates: List<List<Double>>,
    val profile: String
)

