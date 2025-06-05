package com.quentin.navigationapp.model

data class VehicleSubType(
    val label: String,
    val routingType: String
){
    override fun toString(): String = label
}