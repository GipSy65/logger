package com.example.logger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import com.example.logger.CanDataReader
import java.util.*

class SensorDataLogger(private val context: Context) : SensorEventListener, LocationListener {
    private val TAG = "SensorDataLogger"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var isLogging = false
    private val dataList = mutableListOf<SensorData>()

    private var currentYaw: Float? = null
    private var currentPitch: Float? = null
    private var currentRoll: Float? = null
    private var currentLocation: Location? = null
    private var currentCanData: String? = null

    private var dataCallback: ((SensorData) -> Unit)? = null
    private var logsDirectory: File? = null

    private var documentTreeUri: Uri? = null

    private val canDataReader = CanDataReader(context)



    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isLogging) {
                createAndAddDataPoint()
                updateHandler.postDelayed(this, 100)
            }
        }
    }
    init {
        canDataReader.setCanDataCallback { canData ->
            currentCanData = canData
        }
    }
    fun setDocumentTreeUri(uri: Uri) {
        documentTreeUri = uri
    }
    fun startLogging(directory: File, callback: (SensorData) -> Unit) {
        logsDirectory = directory
        startLogging(callback)
    }

    fun startLoggingWithDocumentTree(callback: (SensorData) -> Unit) {
        if (documentTreeUri == null) {
            startLogging(callback)
            return
        }
        logsDirectory = null
        startLogging(callback)
    }

    fun startLogging(callback: (SensorData) -> Unit) {
        if (isLogging) return

        Log.d(TAG, "Starting sensor logging")
        dataCallback = callback
        dataList.clear()

        if (documentTreeUri == null && logsDirectory == null) {
            logsDirectory = File(context.getExternalFilesDir(null), "sensor_logs")
            if (!logsDirectory!!.exists()) {
                logsDirectory!!.mkdirs()
            }
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Rotation vector sensor registered")
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            try {
                val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastLocation != null) {
                    currentLocation = lastLocation
                    Log.d(TAG, "Got last known location: $lastLocation")
                } else {
                    Log.d(TAG, "No last known location available")
                }

                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500,
                    0f,
                    this
                )
                Log.d(TAG, "GPS location updates requested")

                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        500,
                        0f,
                        this
                    )
                    Log.d(TAG, "Network location updates requested")
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting network location updates", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting location updates", e)
            }
        } else {
            Log.w(TAG, "No location permission granted")
        }
        if (!canDataReader.isDeviceConnected()) {
            canDataReader.findUsbDevice()
        }

        isLogging = true

        updateHandler.post(updateRunnable)

        Log.d(TAG, "Logging started")
    }

    fun stopLogging(): String? {
        if (!isLogging) return null

        Log.d(TAG, "Stopping sensor logging")

        updateHandler.removeCallbacks(updateRunnable)

        sensorManager.unregisterListener(this)
        Log.d(TAG, "Sensor listeners unregistered")

        try {
            locationManager.removeUpdates(this)
            Log.d(TAG, "Location updates removed")
        } catch (e: Exception) {

            Log.e(TAG, "Error removing location updates", e)
        }

        isLogging = false
        dataCallback = null

        val filePath = if (documentTreeUri != null) {
            saveWithDocumentTree()
        } else {
            saveDataToFile()
        }

        Log.d(TAG, "Logging stopped, file saved: $filePath")
        return filePath
    }
    fun cleanup() {
        stopLogging()
            canDataReader.cleanup()

    }


    private fun createAndAddDataPoint() {
        val canData = canDataReader.getLatestCanData()

        val data = SensorData(
            timestamp = System.currentTimeMillis(),
            yaw = currentYaw,
            pitch = currentPitch,
            roll = currentRoll,
            location = currentLocation,
            canData = canData
        )

        if (currentYaw != null || currentLocation != null) {
            dataList.add(data)
            dataCallback?.invoke(data)
        }
    }

    private fun saveDataToFile(): String? {
        if (dataList.isEmpty()) {
            Log.d(TAG, "No data to save")
            return null
        }

        try {
            val directory = logsDirectory ?: File(context.getExternalFilesDir(null), "sensor_logs")
            if (!directory.exists()) {
                val dirCreated = directory.mkdirs()
                if (!dirCreated) {
                    Log.e(TAG, "Failed to create directory: ${directory.absolutePath}")

                    val internalDir = File(context.filesDir, "sensor_logs")
                    if (!internalDir.exists()) {
                        internalDir.mkdirs()
                    }
                    return saveToSpecificDirectory(internalDir)
                }
            }

            return saveToSpecificDirectory(directory)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving data to file", e)
            return null
        }
    }

    private fun saveToSpecificDirectory(directory: File): String? {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val file = File(directory, "sensor_log_$timestamp.csv")

            FileWriter(file).use { writer ->
                writer.append(SensorData.getCsvHeader())
                writer.append("\n")

                dataList.forEach { data ->
                    writer.append(data.toCsvString())
                    writer.append("\n")
                }
            }

            Log.d(TAG, "Data saved to: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to file in directory: ${directory.absolutePath}", e)
            return null
        }
    }

    private fun saveWithDocumentTree(): String? {
        if (dataList.isEmpty() || documentTreeUri == null) {
            Log.d(TAG, "No data to save or no document tree URI")
            return null
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val fileName = "sensor_log_$timestamp.csv"

            val treeDoc = DocumentFile.fromTreeUri(context, documentTreeUri!!)
            if (treeDoc == null || !treeDoc.exists()) {
                Log.e(TAG, "Document tree not accessible")
                return saveDataToFile()
            }

            val newFile = treeDoc.createFile("text/csv", fileName)
            if (newFile == null) {
                Log.e(TAG, "Could not create file in document tree")
                return saveDataToFile()
            }

            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter()

                writer.append(SensorData.getCsvHeader())
                writer.append("\n")

                dataList.forEach { data ->
                    writer.append(data.toCsvString())
                    writer.append("\n")
                }

                writer.flush()
            }

            val folderName = treeDoc.name ?: "selected folder"
            val displayPath = "$folderName/$fileName"

            Log.d(TAG, "Data saved to SAF location: $displayPath")
            return displayPath

        } catch (e: Exception) {
            Log.e(TAG, "Error saving with document tree", e)
            return saveDataToFile()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            currentYaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()   // Azimuth
            currentPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat() // Pitch
            currentRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()  // Roll
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    }

    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Location changed: $location")
        currentLocation = location
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.d(TAG, "Location provider status changed: $provider, status: $status")
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Location provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Location provider disabled: $provider")
    }

    companion object {
        private const val TAG = "SensorDataLogger"
    }
}