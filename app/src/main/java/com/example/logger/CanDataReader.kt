package com.example.logger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

class CanDataReader(private val context: Context) {
    companion object {
        private const val TAG = "CanDataReader"
        private const val ACTION_USB_PERMISSION = "com.example.logger.USB_PERMISSION"
        private const val BAUD_RATE = 1000000
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null

    private val executor = Executors.newSingleThreadExecutor()
    private var isConnected = false
    private var latestCanData: String? = null

    private var canDataCallback: ((String) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> synchronized(this) {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                            connectToUsbDevice(it)
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for device: ${device?.deviceName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    findUsbDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                }
            }
        }
    }
    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        findUsbDevice()
    }

    fun setCanDataCallback(callback: (String) -> Unit) {
        canDataCallback = callback
    }

    fun getLatestCanData(): String? = latestCanData

    fun isDeviceConnected(): Boolean = isConnected

    fun findUsbDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            connectToUsbDevice(device)
        }
    }

    private fun connectToUsbDevice(device: UsbDevice) {
        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { it.device == device }
            if (driver == null) {
                return
            }

            val connection: UsbDeviceConnection? = usbManager.openDevice(device)
            if (connection == null) {
                return
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            usbSerialPort = port
            isConnected = true
            startDirectReading()
        } catch (e: IOException) {
            disconnect()
        }
    }

    private fun startDirectReading() {
        if (!isConnected) return

        executor.submit {
            val buffer = ByteArray(1024)

            while (isConnected) {
                try {
                    val port = usbSerialPort ?: break
                    val bytesRead = port.read(buffer, 1000)

                    if (bytesRead > 0) {
                        val data = buffer.copyOfRange(0, bytesRead)

                        val hexString = data.joinToString("") { String.format("%02X", it) }
                        latestCanData = hexString
                        canDataCallback?.invoke(hexString)
                    }
                    Thread.sleep(5)
                } catch (e: Exception) {
                    if (isConnected) {
                        Log.e(TAG, "Error reading from USB", e)
                        disconnect()
                    }
                    break
                }
            }
        }
    }

    fun disconnect() {
        isConnected = false

        try {
            usbSerialPort?.close()
        } catch (_: IOException) {}

        usbSerialPort = null
    }

    fun cleanup() {
        isConnected = false

        try {
            context.unregisterReceiver(usbReceiver)
            Log.d(TAG, "USB receiver unregistered successfully")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "USB receiver was not registered: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering USB receiver", e)
        }

        // Shutdown executor
        try {
            executor.shutdown()
            if (!executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
            Log.d(TAG, "Executor shutdown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down executor", e)
            executor.shutdownNow()
        }

        canDataCallback = null

    }
}