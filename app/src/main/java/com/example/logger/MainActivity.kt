package com.example.logger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.hoho.android.usbserial.driver.UsbSerialProber

@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var sensorLogger: SensorDataLogger

    private val isLogging = mutableStateOf(false)
    private val sensorDataText = mutableStateOf("Waiting for data...")

    private var customLogDir: File? = null

    private var documentTreeUri: Uri? = null
    private val CREATE_DOCUMENT_TREE_REQUEST_CODE = 1234

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLogging()
        } else {
            Toast.makeText(
                this,
                "Location permission is required for GPS data",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pickCustomDirectory()
        } else {
            Toast.makeText(
                this,
                "Storage permission denied. Files will be saved to app-specific storage.",
                Toast.LENGTH_LONG
            ).show()
            setupFallbackDirectory()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::sensorLogger.isInitialized) {
            sensorLogger.cleanup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            sensorLogger = SensorDataLogger(this)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                checkAndRequestManageExternalStoragePermission()
            }

            val prefs = getSharedPreferences("LoggerPrefs", Context.MODE_PRIVATE)

            val savedUriString = prefs.getString("DOCUMENT_TREE_URI", null)
            if (savedUriString != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    documentTreeUri = Uri.parse(savedUriString)
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(documentTreeUri!!, takeFlags)

                    sensorLogger.setDocumentTreeUri(documentTreeUri!!)

                    Toast.makeText(
                        this,
                        "Using previously selected folder",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error accessing saved document tree URI", e)
                    documentTreeUri = null
                }
            }

            if (documentTreeUri == null) {
                val savedFolderPath = prefs.getString("CUSTOM_FOLDER_PATH", null)
                if (savedFolderPath != null) {
                    val savedFolder = File(savedFolderPath)
                    if (savedFolder.exists() && savedFolder.canWrite()) {
                        customLogDir = savedFolder
                        Toast.makeText(
                            this,
                            "Using saved folder: ${customLogDir!!.absolutePath}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        setupFallbackDirectory()
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setupFallbackDirectory()
                    } else {
                        if (ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            setupCustomDirectory()
                        } else {
                            requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                }
            }

            setContent {
                MaterialTheme {
                    SimpleLoggerApp(
                        isLogging = isLogging.value,
                        sensorDataText = sensorDataText.value,
                        onStartClick = { checkPermissionAndStartLogging() },
                        onStopClick = { stopLogging() },
                        onPickDirectoryClick = { pickCustomDirectory() }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                    Toast.makeText(this, "Please grant all files access permission", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting MANAGE_EXTERNAL_STORAGE", e)
                    Toast.makeText(this, "Cannot request all files access permission", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupCustomDirectory() {
        try {
            val externalDir = Environment.getExternalStorageDirectory()
            val appDir = File(externalDir, "com.example.logger")
            if (!appDir.exists()) {
                val dirCreated = appDir.mkdirs()
                if (!dirCreated) {
                    Log.e(TAG, "Failed to create app directory")
                    setupFallbackDirectory()
                    return
                }
            }

            customLogDir = File(appDir, "sensor_logs")
            if (!customLogDir!!.exists()) {
                val dirCreated = customLogDir!!.mkdirs()
                if (!dirCreated) {
                    Log.e(TAG, "Failed to create sensor_logs directory")
                    setupFallbackDirectory()
                    return
                }
            }

            Toast.makeText(this, "Files will be saved to: ${customLogDir!!.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up custom directory", e)
            setupFallbackDirectory()
        }
    }

    private fun setupFallbackDirectory() {
        customLogDir = File(getExternalFilesDir(null), "sensor_logs")
        if (!customLogDir!!.exists()) {
            customLogDir!!.mkdirs()
        }
        Toast.makeText(this, "Using app-specific directory: ${customLogDir!!.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun pickCustomDirectory() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            launchSafDirectoryPicker()
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                showFolderNameDialog()
            } else {
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun launchSafDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, CREATE_DOCUMENT_TREE_REQUEST_CODE)
    }

    private fun showFolderNameDialog() {
        val prefs = getSharedPreferences("LoggerPrefs", Context.MODE_PRIVATE)
        val savedFolderName = prefs.getString("CUSTOM_FOLDER_NAME", "")

        var folderName by mutableStateOf(savedFolderName ?: "")

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("Create Folder")
            .setMessage("Enter folder name:")
            .setView(
                EditText(this).apply {
                    setText(savedFolderName)
                    hint = "Folder name"
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            folderName = s.toString()
                        }
                    })
                }
            )
            .setPositiveButton("Create") { _, _ ->
                if (folderName.isNotEmpty()) {
                    createCustomFolder(folderName)
                } else {
                    Toast.makeText(this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)

        dialogBuilder.show()
    }

    private fun createCustomFolder(folderName: String) {
        try {
            val externalDir = Environment.getExternalStorageDirectory()

            val customFolder = File(externalDir, folderName)

            if (!customFolder.exists()) {
                val success = customFolder.mkdirs()
                if (success) {
                    customLogDir = File(customFolder, "sensor_logs")
                    if (!customLogDir!!.exists()) {
                        customLogDir!!.mkdirs()
                    }

                    val prefs = getSharedPreferences("LoggerPrefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("CUSTOM_FOLDER_PATH", customLogDir!!.absolutePath)
                        .putString("CUSTOM_FOLDER_NAME", folderName)
                        .apply()

                    Toast.makeText(
                        this,
                        "Files will be saved to: ${customLogDir!!.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show()
                    setupFallbackDirectory()
                }
            } else {
                customLogDir = File(customFolder, "sensor_logs")
                if (!customLogDir!!.exists()) {
                    customLogDir!!.mkdirs()
                }

                val prefs = getSharedPreferences("LoggerPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("CUSTOM_FOLDER_PATH", customLogDir!!.absolutePath)
                    .putString("CUSTOM_FOLDER_NAME", folderName)
                    .apply()

                Toast.makeText(
                    this,
                    "Using existing folder: ${customLogDir!!.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating custom folder", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            setupFallbackDirectory()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CREATE_DOCUMENT_TREE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    documentTreeUri = uri
                    val prefs = getSharedPreferences("LoggerPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("DOCUMENT_TREE_URI", uri.toString()).apply()

                    sensorLogger.setDocumentTreeUri(uri)

                    val documentFile = DocumentFile.fromTreeUri(this, uri)
                    val folderName = documentFile?.name ?: "Selected Folder"

                    Toast.makeText(
                        this,
                        "Selected folder: $folderName",
                        Toast.LENGTH_LONG
                    ).show()

                    customLogDir = null
                    prefs.edit().remove("CUSTOM_FOLDER_PATH").apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Error with document tree URI", e)
                    Toast.makeText(this, "Error selecting folder: ${e.message}", Toast.LENGTH_LONG).show()
                    setupFallbackDirectory()
                }
            }
        }
    }

    private fun checkPermissionAndStartLogging() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Location Services Required")
                .setMessage("This app needs location services to log GPS data. Please enable location services to continue.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (customLogDir == null && documentTreeUri == null) {
            setupFallbackDirectory()
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                startLogging()
            }
            else -> {
                requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun startLogging() {
        try {
            sensorDataText.value = ""

            if (documentTreeUri != null) {
                sensorLogger.startLoggingWithDocumentTree { data ->
                    updateSensorDataText(data)
                }
            } else if (customLogDir != null) {
                sensorLogger.startLogging(customLogDir!!) { data ->
                    updateSensorDataText(data)
                }
            } else {
                sensorLogger.startLogging { data ->
                    updateSensorDataText(data)
                }
            }

            isLogging.value = true

            Toast.makeText(this, "Logging started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting logging", e)
            Toast.makeText(this, "Error starting logging: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSensorDataText(data: SensorData) {
        sensorDataText.value = """
            Yaw: ${data.yaw?.let { String.format("%.2f°", it) } ?: "N/A"}
            Pitch: ${data.pitch?.let { String.format("%.2f°", it) } ?: "N/A"}
            Roll: ${data.roll?.let { String.format("%.2f°", it) } ?: "N/A"}
            
            Location:
              Lat: ${data.location?.let { String.format("%.6f", it.latitude) } ?: "N/A"}
              Lng: ${data.location?.let { String.format("%.6f", it.longitude) } ?: "N/A"}
              Alt: ${data.location?.let { String.format("%.1f m", it.altitude) } ?: "N/A"}
              
              CAN Data: ${data.canData ?: "Not connected"}

        """.trimIndent()
    }

    private fun stopLogging() {
        try {
            val filePath = sensorLogger.stopLogging()

            isLogging.value = false

            if (filePath != null) {
                Toast.makeText(
                    this,
                    "Data saved to: $filePath",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "No data to save or error occurred",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logging", e)
            Toast.makeText(this, "Error stopping logging: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun SimpleLoggerApp(
    isLogging: Boolean,
    sensorDataText: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPickDirectoryClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Logger",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = onPickDirectoryClick,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = "Pick Directory",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text(
            text = "Status: ${if (isLogging) "Logging..." else "Ready"}",
            fontSize = 18.sp,
            color = if (isLogging) Color(0xFF4CAF50) else Color.Gray,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    color = Color(0xFFF5F5F5),
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color(0xFFE0E0E0),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                text = sensorDataText,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                lineHeight = 24.sp,
                color = Color(0xFF333333)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartClick,
                enabled = !isLogging,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .padding(end = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50), // Green
                    disabledContainerColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                )
            ) {
                Text("START", fontSize = 16.sp)
            }

            Button(
                onClick = onStopClick,
                enabled = isLogging,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336), // Red
                    disabledContainerColor = Color(0xFFF44336).copy(alpha = 0.5f)
                )
            ) {
                Text("STOP", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}