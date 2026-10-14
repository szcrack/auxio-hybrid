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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages USB DAC detection, connection state, and hardware audio streaming.
 *
 * SAFETY: All JNI / native calls run on a dedicated background thread to prevent
 * SIGABRT crashes from propagating to the main thread when a USB device is connected.
 */
@Singleton
class UsbDacManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()
    private var nativeHandle: Long = 0L

    /** Worker thread for all JNI operations - avoids crashing main thread */
    private val nativeThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "UsbDac-Native").also { it.isDaemon = true }
    }

    var isDacConnected: Boolean = false
        private set

    var currentDeviceName: String? = null
        private set

    private val _dacConnectedFlow = MutableStateFlow(false)
    val dacConnectedFlow: StateFlow<Boolean> = _dacConnectedFlow

    private val _deviceNameFlow = MutableStateFlow<String?>(null)
    val deviceNameFlow: StateFlow<String?> = _deviceNameFlow

    private var onDacStateChangedListener: ((Boolean, String?) -> Unit)? = null

    companion object {
        private const val TAG = "UsbDacManager"
        private const val ACTION_USB_PERMISSION = "org.oxycblt.auxio.USB_PERMISSION"
        const val SUBCLASS_AUDIO_CONTROL = 1
        const val SUBCLASS_AUDIO_STREAMING = 2

        /** True si las librerias nativas cargaron correctamente */
        val nativeLibsAvailable: Boolean = try {
            // NativeBridge companion object loads libs in its init block
            NativeBridge
            true
        } catch (_: Throwable) {
            false
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // IMPORTANT: onReceive runs on main thread. Never call JNI here directly.
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDevice(intent) ?: return
                    if (isAudioDevice(device)) {
                        Log.i(TAG, "USB Audio device attached: ${device.deviceName}")
                        requestPermissionAndOpen(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDevice(intent) ?: return
                    if (isAudioDevice(device)) {
                        Log.i(TAG, "USB Audio device detached: ${device.deviceName}")
                        // Schedule close on native thread to avoid main thread JNI
                        nativeThread.execute { closeDac() }
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDevice(intent) ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Log.i(TAG, "USB permission granted for: ${device.productName}")
                        // Run on native thread - NOT main thread
                        nativeThread.execute { openDeviceInternal(device) }
                    } else {
                        Log.w(TAG, "USB permission denied for: ${device.productName}")
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering USB receiver", e)
        }
        // Scan on native thread to avoid blocking service startup
        nativeThread.execute { scanForConnectedDac() }
    }

    fun release() {
        try { context.unregisterReceiver(usbReceiver) } catch (_: Throwable) {}
        nativeThread.execute { closeDac() }
    }

    fun scanForConnectedDac() {
        try {
            val deviceList = usbManager.deviceList
            for ((_, device) in deviceList) {
                if (isAudioDevice(device)) {
                    Log.i(TAG, "Found existing USB Audio device: ${device.productName}")
                    requestPermissionAndOpen(device)
                    break
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error scanning USB devices", e)
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        return try {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    (intf.interfaceSubclass == SUBCLASS_AUDIO_STREAMING ||
                     intf.interfaceSubclass == SUBCLASS_AUDIO_CONTROL)
                ) return true
            }
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking USB device class", e)
            false
        }
    }

    private fun requestPermissionAndOpen(device: UsbDevice) {
        try {
            if (usbManager.hasPermission(device)) {
                // Already have permission - open on native thread
                nativeThread.execute { openDeviceInternal(device) }
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
        } catch (e: Throwable) {
            Log.e(TAG, "Error requesting USB permission", e)
        }
    }

    /**
     * Opens the USB device and initializes native audio driver.
     * MUST be called from [nativeThread] only, never from main thread.
     */
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
                try {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                        if (conn.claimInterface(intf, true)) {
                            claimedInterfaces.add(intf)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not claim interface $i", e)
                }
            }

            // Try native driver only if libs loaded successfully
            if (nativeLibsAvailable) {
                try {
                    val rawDescriptors = conn.rawDescriptors ?: ByteArray(0)
                    nativeHandle = NativeBridge.nativeOpen(conn.fileDescriptor, rawDescriptors)
                    if (nativeHandle != 0L) {
                        Log.i(TAG, "TrueSample native driver bound. Handle=$nativeHandle")
                    } else {
                        Log.w(TAG, "nativeOpen returned 0 - running in monitor-only mode")
                    }
                } catch (e: Throwable) {
                    // Native crash - catch here since we are NOT on main thread
                    Log.e(TAG, "Native driver failed to open (non-fatal, monitor mode)", e)
                    nativeHandle = 0L
                }
            } else {
                Log.w(TAG, "Native libs not available - running in USB monitor-only mode")
            }

            // Update connection state regardless of native result
            isDacConnected = true
            currentDeviceName = device.productName ?: device.deviceName
            _dacConnectedFlow.value = true
            _deviceNameFlow.value = currentDeviceName
            onDacStateChangedListener?.invoke(true, currentDeviceName)
            Log.i(TAG, "USB DAC connected: $currentDeviceName (native=${nativeHandle != 0L})")

        } catch (e: Throwable) {
            Log.e(TAG, "openDeviceInternal failed", e)
            closeDac()
        }
    }

    fun startPlayback(sampleRate: Int, bitDepth: Int, channels: Int, bufferSize: Int = 1024): Boolean {
        if (!isDacConnected || nativeHandle == 0L) return false
        return try {
            val result = NativeBridge.nativeStart(
                handle = nativeHandle,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                bufferSize = bufferSize,
                flags = 0,
                mode = 0
            )
            result.isNotEmpty() && result[0] == 0
        } catch (e: Throwable) {
            Log.e(TAG, "startPlayback failed", e)
            false
        }
    }

    fun writePcmBuffer(buffer: ByteBuffer, length: Int): Int {
        if (!isDacConnected || nativeHandle == 0L) return -1
        return try {
            NativeBridge.nativeWrite(nativeHandle, buffer, length)
        } catch (e: Throwable) {
            Log.e(TAG, "writePcmBuffer failed", e)
            -1
        }
    }

    fun stopPlayback() {
        if (nativeHandle == 0L) return
        try {
            NativeBridge.nativeDrain(nativeHandle)
            NativeBridge.nativeStop(nativeHandle)
        } catch (e: Throwable) {
            Log.e(TAG, "stopPlayback failed", e)
        }
    }

    fun setHardwareVolume(volume: Int): Boolean {
        if (nativeHandle == 0L) return false
        return try {
            NativeBridge.nativeSetVolume(nativeHandle, volume)
        } catch (e: Throwable) {
            Log.e(TAG, "setHardwareVolume failed", e)
            false
        }
    }

    fun closeDac() {
        if (nativeHandle != 0L) {
            try {
                NativeBridge.nativeStop(nativeHandle)
                NativeBridge.nativeClose(nativeHandle)
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing native handle", e)
            }
            nativeHandle = 0L
        }
        usbConnection?.let { conn ->
            for (intf in claimedInterfaces) {
                try { conn.releaseInterface(intf) } catch (_: Throwable) {}
            }
            claimedInterfaces.clear()
            try { conn.close() } catch (_: Throwable) {}
        }
        usbConnection = null
        val wasConnected = isDacConnected
        isDacConnected = false
        currentDeviceName = null
        _dacConnectedFlow.value = false
        _deviceNameFlow.value = null
        if (wasConnected) {
            onDacStateChangedListener?.invoke(false, null)
        }
    }

    private fun getUsbDevice(intent: Intent): UsbDevice? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting USB device from intent", e)
            null
        }
    }
}
