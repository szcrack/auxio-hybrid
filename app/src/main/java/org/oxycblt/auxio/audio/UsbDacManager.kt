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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.truesample.usbaudio.NativeBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestor avanzado de DAC y Audifonos USB:
 * - Detecta dispositivos USB Audio via UsbManager (Hardware USB directo)
 * - Detecta dispositivos USB Audio via AudioManager (AudioDeviceInfo TYPE_USB_DEVICE / TYPE_USB_HEADSET)
 * - Provee escucha en tiempo real sin crasheos en el hilo principal
 */
@Singleton
class UsbDacManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var usbConnection: UsbDeviceConnection? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()
    private var nativeHandle: Long = 0L

    private val nativeThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UsbDac-Worker").also { it.isDaemon = true }
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

        val nativeLibsAvailable: Boolean = try {
            NativeBridge
            true
        } catch (_: Throwable) {
            false
        }
    }

    // --- AudioDeviceCallback para detectar inserción de USB en AudioManager ---
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices added to system, checking USB...")
            nativeThread.execute { scanForConnectedDac() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices removed from system, checking USB...")
            nativeThread.execute { scanForConnectedDac() }
        }
    }

    // --- BroadcastReceiver para eventos de hardware USB OTG ---
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDevice(intent) ?: return
                    Log.i(TAG, "USB attached: ${device.deviceName} (class=${device.deviceClass})")
                    if (isAudioDevice(device)) {
                        requestPermissionAndOpen(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDevice(intent) ?: return
                    Log.i(TAG, "USB detached: ${device.deviceName}")
                    nativeThread.execute {
                        closeDac()
                        scanAudioManagerDevices()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDevice(intent) ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Log.i(TAG, "Permission granted for: ${device.productName}")
                        nativeThread.execute { openDeviceInternal(device) }
                    } else {
                        Log.w(TAG, "Permission denied for: ${device.productName}, fallback a AudioManager")
                        scanAudioManagerDevices()
                    }
                }
            }
        }
    }

    fun init(listener: ((Boolean, String?) -> Unit)? = null) {
        onDacStateChangedListener = listener

        // 1. Registrar receiver USB
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
            Log.e(TAG, "Error registrando usbReceiver", e)
        }

        // 2. Registrar AudioDeviceCallback
        try {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Throwable) {
            Log.e(TAG, "Error registrando audioDeviceCallback", e)
        }

        // 3. Escaneo inicial
        nativeThread.execute { scanForConnectedDac() }
    }

    fun release() {
        try { context.unregisterReceiver(usbReceiver) } catch (_: Throwable) {}
        try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Throwable) {}
        nativeThread.execute { closeDac() }
    }

    /**
     * Escanea dispositivos USB conectados combinando UsbManager y AudioManager.
     */
    fun scanForConnectedDac() {
        try {
            // Intentar primero via UsbManager (Hardware directo)
            val deviceList = usbManager.deviceList
            var foundUsbDevice = false

            for ((_, device) in deviceList) {
                if (isAudioDevice(device)) {
                    Log.i(TAG, "DAC USB detectado en UsbManager: ${device.productName ?: device.deviceName}")
                    foundUsbDevice = true
                    requestPermissionAndOpen(device)
                    break
                }
            }

            // Si no se encontro en UsbManager o no dio permiso, escanear via AudioManager
            if (!foundUsbDevice) {
                scanAudioManagerDevices()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error en scanForConnectedDac", e)
            scanAudioManagerDevices()
        }
    }

    /**
     * Escanea dispositivos de salida activos en Android AudioManager (detecta adaptadores y audífonos USB-C).
     */
    private fun scanAudioManagerDevices() {
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var usbFound = false
            var foundName = "Audifonos USB"

            for (dev in outputs) {
                if (dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                ) {
                    usbFound = true
                    val prodName = dev.productName?.toString()?.trim()
                    if (!prodName.isNullOrEmpty()) {
                        foundName = prodName
                    }
                    break
                }
            }

            if (usbFound) {
                isDacConnected = true
                currentDeviceName = foundName
                _dacConnectedFlow.value = true
                _deviceNameFlow.value = currentDeviceName
                onDacStateChangedListener?.invoke(true, currentDeviceName)
                Log.i(TAG, "DAC / Audifonos USB confirmados en AudioManager: $foundName")
            } else {
                if (!isDacConnected) {
                    _dacConnectedFlow.value = false
                    _deviceNameFlow.value = null
                    onDacStateChangedListener?.invoke(false, null)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error escaneando AudioManager", e)
        }
    }

    /**
     * Comprobación flexible: detecta DACs con clase Audio en el dispositivo o en cualquiera de sus interfaces.
     */
    private fun isAudioDevice(device: UsbDevice): Boolean {
        return try {
            if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Error verificando clase de dispositivo USB", e)
            false
        }
    }

    private fun requestPermissionAndOpen(device: UsbDevice) {
        try {
            if (usbManager.hasPermission(device)) {
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
            Log.e(TAG, "Error pidiendo permiso USB", e)
            scanAudioManagerDevices()
        }
    }

    private fun openDeviceInternal(device: UsbDevice) {
        try {
            closeDac()
            val conn = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "No se pudo abrir UsbDeviceConnection para ${device.productName}")
                scanAudioManagerDevices()
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
                    Log.w(TAG, "No se pudo reclamar interfaz $i", e)
                }
            }

            if (nativeLibsAvailable) {
                try {
                    val rawDescriptors = conn.rawDescriptors ?: ByteArray(0)
                    nativeHandle = NativeBridge.nativeOpen(conn.fileDescriptor, rawDescriptors)
                    if (nativeHandle != 0L) {
                        Log.i(TAG, "Driver nativo UAC2 enlazado correctamente. Handle=$nativeHandle")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Driver nativo fallo (modo estándar activo)", e)
                    nativeHandle = 0L
                }
            }

            isDacConnected = true
            currentDeviceName = device.productName ?: device.deviceName ?: "Dispositivo USB Audio Class"
            _dacConnectedFlow.value = true
            _deviceNameFlow.value = currentDeviceName
            onDacStateChangedListener?.invoke(true, currentDeviceName)
            Log.i(TAG, "USB DAC enlazado exitosamente: $currentDeviceName (bitperfect=${nativeHandle != 0L})")

        } catch (e: Throwable) {
            Log.e(TAG, "openDeviceInternal fallo", e)
            scanAudioManagerDevices()
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
            Log.e(TAG, "startPlayback fallo", e)
            false
        }
    }

    fun writePcmBuffer(buffer: ByteBuffer, length: Int): Int {
        if (!isDacConnected || nativeHandle == 0L) return -1
        return try {
            NativeBridge.nativeWrite(nativeHandle, buffer, length)
        } catch (e: Throwable) {
            Log.e(TAG, "writePcmBuffer fallo", e)
            -1
        }
    }

    fun stopPlayback() {
        if (nativeHandle == 0L) return
        try {
            NativeBridge.nativeDrain(nativeHandle)
            NativeBridge.nativeStop(nativeHandle)
        } catch (e: Throwable) {
            Log.e(TAG, "stopPlayback fallo", e)
        }
    }

    fun setHardwareVolume(volume: Int): Boolean {
        if (nativeHandle == 0L) return false
        return try {
            NativeBridge.nativeSetVolume(nativeHandle, volume)
        } catch (e: Throwable) {
            Log.e(TAG, "setHardwareVolume fallo", e)
            false
        }
    }

    fun closeDac() {
        if (nativeHandle != 0L) {
            try {
                NativeBridge.nativeStop(nativeHandle)
                NativeBridge.nativeClose(nativeHandle)
            } catch (e: Throwable) {
                Log.e(TAG, "Error cerrando nativeHandle", e)
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
            Log.e(TAG, "Error obteniendo UsbDevice", e)
            null
        }
    }
}
