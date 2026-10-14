package org.oxycblt.auxio.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentUsbDacBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import javax.inject.Inject

@AndroidEntryPoint
class UsbDacFragment : ViewBindingFragment<FragmentUsbDacBinding>() {

    @Inject
    lateinit var usbDacManager: UsbDacManager

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentUsbDacBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentUsbDacBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // Observe DAC connection state in real time
        viewLifecycleOwner.lifecycleScope.launch {
            usbDacManager.dacConnectedFlow.collectLatest { isConnected ->
                updateDacState(binding, isConnected, usbDacManager.currentDeviceName)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            usbDacManager.deviceNameFlow.collectLatest { name ->
                if (usbDacManager.isDacConnected) {
                    binding.txtDeviceName.text = name ?: "Dispositivo USB Audio Class 2"
                }
            }
        }

        binding.btnScanDac.setOnClickListener {
            Toast.makeText(requireContext(), "Buscando DAC USB conectado...", Toast.LENGTH_SHORT).show()
            usbDacManager.scanForConnectedDac()
        }

        binding.seekHardwareVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtVolumeLabel.text = "${getString(R.string.lbl_dac_hardware_vol)}: $progress%"
                    usbDacManager.setHardwareVolume(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateDacState(binding: FragmentUsbDacBinding, isConnected: Boolean, deviceName: String?) {
        if (isConnected) {
            binding.txtStatusTitle.text = getString(R.string.lbl_dac_status_connected)
            binding.txtDeviceName.text = deviceName ?: "Dispositivo USB Audio Class 2"
            binding.txtBypassBadge.text = getString(R.string.lbl_dac_bitperfect_active)
            binding.imgStatusIcon.setImageResource(R.drawable.ic_usb_dac_24)
        } else {
            binding.txtStatusTitle.text = getString(R.string.lbl_dac_status_disconnected)
            binding.txtDeviceName.text = getString(R.string.lbl_dac_standard_active)
            binding.txtBypassBadge.text = getString(R.string.lbl_dac_standard_active)
            binding.imgStatusIcon.setImageResource(R.drawable.ic_usb_dac_24)
        }
    }
}
