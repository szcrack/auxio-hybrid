package org.oxycblt.auxio.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.truesample.usbaudio.NativeBridge
import java.nio.ByteBuffer

/**
 * Manages USB DAC detection, direct connection, and hardware audio streaming
 * via TrueSample's native driver (libusbaudio.so).
 */
class UsbDacManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var claimedInterfaces = mutableListOf<UsbInterface>()
    private var nativeHandle: Long = 0L

    var isDacConnected: Boolean = false
        private set

    var currentDeviceName: String? = null
        private set

    private var onDacStateChangedListener: ((Boolean, String?) -> Unit)? = null

    companion object {
        private const val TAG = "UsbDacManager"
        private const val ACTION_USB_PERMISSION = "org.oxycblt.auxio.USB_PERMISSION"
        const val SUBCLASS_AUDIO_CONTROL = 1
        const val SUBCLASS_AUDIO_STREAMING = 2
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDevice(intent)
                    if (device != null && isAudioDevice(device)) {
                        Log.i(TAG, "Audio DAC attached: ${device.deviceName} (${device.productName})")
                        requestPermissionAndOpen(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDevice(intent)
                    if (device != null && isAudioDevice(device)) {
                        Log.i(TAG, "Audio DAC detached: ${device.deviceName}")
                        closeDac()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = getUsbDevice(intent)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            Log.i(TAG, "Permission granted for DAC: ${device.productName}")
                            openDeviceInternal(device)
                        } else {
                            Log.w(TAG, "Permission denied for DAC: ${device?.productName}")
                        }
                    }
                }
            }
        }
    }

    fun init(listener: ((Boolean, String?) -> Unit)? = null) {
        onDacStateChangedListener = listener
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        scanForConnectedDac()
    }

    fun release() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        closeDac()
    }

    fun scanForConnectedDac() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isAudioDevice(device)) {
                Log.i(TAG, "Found existing USB Audio DAC: ${device.productName}")
                requestPermissionAndOpen(device)
                break
            }
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                (intf.interfaceSubclass == SUBCLASS_AUDIO_STREAMING || intf.interfaceSubclass == SUBCLASS_AUDIO_CONTROL)
            ) {
                return true
            }
        }
        return false
    }

    private fun requestPermissionAndOpen(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openDeviceInternal(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun openDeviceInternal(device: UsbDevice) {
        try {
            closeDac()
            val conn = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "Failed to open UsbDeviceConnection for ${device.productName}")
                return
            }
            usbConnection = conn

            claimedInterfaces.clear()
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    conn.claimInterface(intf, true)
                    claimedInterfaces.add(intf)
                }
            }

            val rawDescriptors = conn.rawDescriptors ?: ByteArray(0)
            nativeHandle = NativeBridge.nativeOpen(conn.fileDescriptor, rawDescriptors)

            if (nativeHandle != 0L) {
                isDacConnected = true
                currentDeviceName = device.productName ?: device.deviceName
                Log.i(TAG, "TrueSample USB Driver successfully bound! Handle: $nativeHandle")
                onDacStateChangedListener?.invoke(true, currentDeviceName)
            } else {
                Log.e(TAG, "NativeBridge.nativeOpen returned 0L handle")
                closeDac()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening DAC device", e)
            closeDac()
        }
    }

    fun startPlayback(sampleRate: Int, bitDepth: Int, channels: Int, bufferSize: Int = 1024): Boolean {
        if (!isDacConnected || nativeHandle == 0L) return false
        val result = NativeBridge.nativeStart(
            handle = nativeHandle,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            bufferSize = bufferSize,
            flags = 0,
            mode = 0
        )
        return result.isNotEmpty() && result[0] == 0
    }

    fun writePcmBuffer(buffer: ByteBuffer, length: Int): Int {
        if (!isDacConnected || nativeHandle == 0L) return -1
        return NativeBridge.nativeWrite(nativeHandle, buffer, length)
    }

    fun stopPlayback() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeDrain(nativeHandle)
            NativeBridge.nativeStop(nativeHandle)
        }
    }

    fun setHardwareVolume(volume: Int): Boolean {
        if (nativeHandle == 0L) return false
        return NativeBridge.nativeSetVolume(nativeHandle, volume)
    }

    fun closeDac() {
        if (nativeHandle != 0L) {
            try {
                NativeBridge.nativeStop(nativeHandle)
                NativeBridge.nativeClose(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing native handle", e)
            }
            nativeHandle = 0L
        }

        usbConnection?.let { conn ->
            for (intf in claimedInterfaces) {
                try {
                    conn.releaseInterface(intf)
                } catch (_: Exception) {}
            }
            claimedInterfaces.clear()
            conn.close()
        }
        usbConnection = null
        val wasConnected = isDacConnected
        isDacConnected = false
        currentDeviceName = null
        if (wasConnected) {
            onDacStateChangedListener?.invoke(false, null)
        }
    }

    private fun getUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
