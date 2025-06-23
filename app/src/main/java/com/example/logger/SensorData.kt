package com.example.logger

import android.location.Location
import java.text.SimpleDateFormat
import java.util.*

data class SensorData(
    val timestamp: Long,
    val yaw: Float? = null,
    val pitch: Float? = null,
    val roll: Float? = null,
    val location: Location? = null,
    val canData: String? = null
) {
    override fun toString(): String {
        return "Time: ${timestamp}\n" +
                "Yaw: ${yaw?.let { String.format("%.2f", it) } ?: "N/A"}\n" +
                "Pitch: ${pitch?.let { String.format("%.2f", it) } ?: "N/A"}\n" +
                "Roll: ${roll?.let { String.format("%.2f", it) } ?: "N/A"}\n" +
                "Location: ${location?.let { "(${it.latitude}, ${it.longitude})" } ?: "N/A"}\n"
        "CAN Data: ${canData ?: "N/A"}\n"
    }

    fun toCsvString(): String {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm:ss:SSS", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        val formattedTime = "\"" + sdf.format(Date(timestamp)) + "\""

        return "$formattedTime," +
                "${yaw?.let { String.format("%.2f", it) } ?: ""}," +
                "${pitch?.let { String.format("%.2f", it) } ?: ""}," +
                "${roll?.let { String.format("%.2f", it) } ?: ""}," +
                "${location?.latitude ?: ""}," +
                "${location?.longitude ?: ""}," +
//                "${location?.altitude ?: ""}," +
                "${canData ?: ""}"
    }

    companion object {
        fun getCsvHeader(): String {
            return "timestamp,yaw,pitch,roll,latitude,longitude,can_data"
        }
    }
}