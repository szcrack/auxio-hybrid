package org.oxycblt.auxio.downloader

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentSpotiflacBinding
import org.oxycblt.auxio.databinding.ItemSpotiflacTrackBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import javax.inject.Inject

@AndroidEntryPoint
class SpotiFlacFragment : ViewBindingFragment<FragmentSpotiflacBinding>() {

    @Inject lateinit var downloader: SpotiFlacDownloader
    @Inject lateinit var deezerProvider: DeezerProvider
    @Inject lateinit var ccMixterProvider: CcMixterProvider
    @Inject lateinit var archiveProvider: ArchiveProvider

    private val trackAdapter = TrackAdapter()
    private var activeProvider: MusicProvider? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentSpotiflacBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentSpotiflacBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        try {
            // Proveedor activo segun preferencia guardada
            activeProvider = when (downloader.activeProviderKey) {
                "ccmixter" -> ccMixterProvider
                "archive" -> archiveProvider
                else -> deezerProvider
            }

            updateProviderChip(binding)

            binding.recyclerTracks.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = trackAdapter
            }

            // Boton buscar
            binding.btnSearch.setOnClickListener { performSearch(binding) }

            binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    performSearch(binding)
                    true
                } else false
            }

            // Chip selector de proveedor
            binding.chipProvider.setOnClickListener {
                showProviderDialog(binding)
            }

            // Boton ARL (solo visible para Deezer)
            binding.btnArl.setOnClickListener {
                showArlDialog()
            }

        } catch (e: Throwable) {
            android.util.Log.e("SpotiFlacFragment", "onBindingCreated failed", e)
            binding.emptyText.text = "Error al inicializar: ${e.localizedMessage}"
            binding.emptyText.isVisible = true
        }
    }

    private fun updateProviderChip(binding: FragmentSpotiflacBinding) {
        val provider = activeProvider ?: deezerProvider
        binding.chipProvider.text = provider.name
        binding.btnArl.isVisible = (provider is DeezerProvider)
        binding.txtProviderDesc.text = provider.description
    }

    private fun showProviderDialog(binding: FragmentSpotiflacBinding) {
        val providers = listOf(
            "deezer" to "Deezer — 30M canciones (preview gratis, FLAC con cuenta)",
            "ccmixter" to "ccMixter — Musica Creative Commons libre",
            "archive" to "Internet Archive — Conciertos live + clasicos FLAC"
        )
        val names = providers.map { it.second }.toTypedArray()
        val currentIdx = providers.indexOfFirst { it.first == downloader.activeProviderKey }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar fuente de musica")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val key = providers[which].first
                downloader.activeProviderKey = key
                activeProvider = when (key) {
                    "ccmixter" -> ccMixterProvider
                    "archive" -> archiveProvider
                    else -> deezerProvider
                }
                updateProviderChip(binding)
                dialog.dismiss()
                Toast.makeText(requireContext(), "Fuente: ${activeProvider?.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showArlDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Pega tu ARL de Deezer aqui"
            setText(downloader.deezerArl)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("ARL de Deezer Premium")
            .setMessage("El ARL es una cookie de sesion de tu cuenta Deezer. Con cuenta Premium/HiFi puedes descargar FLAC completo.\n\nSin ARL: preview gratis de 30 segundos.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                downloader.deezerArl = input.text.toString()
                val msg = if (downloader.hasArl) "ARL guardado. Descargas FLAC activadas." else "ARL borrado. Modo preview."
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performSearch(binding: FragmentSpotiflacBinding) {
        val query = binding.searchInput.text?.toString()?.trim() ?: return
        if (query.isEmpty()) return

        binding.searchProgress.isVisible = true
        binding.emptyText.isVisible = false
        binding.recyclerTracks.isVisible = false

        val provider = activeProvider ?: deezerProvider

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = provider.search(query)
                binding.searchProgress.isVisible = false

                result.onSuccess { tracks ->
                    if (tracks.isEmpty()) {
                        binding.emptyText.text = getString(R.string.lbl_spotiflac_empty)
                        binding.emptyText.isVisible = true
                    } else {
                        binding.recyclerTracks.isVisible = true
                        trackAdapter.submitList(tracks)

                        // Info al usuario segun proveedor
                        when (provider) {
                            is DeezerProvider -> if (!downloader.hasArl) {
                                Toast.makeText(
                                    requireContext(),
                                    "Preview 30s. Configura tu ARL Deezer para FLAC completo.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is CcMixterProvider -> Toast.makeText(
                                requireContext(),
                                "Musica Creative Commons — descarga libre",
                                Toast.LENGTH_SHORT
                            ).show()
                            is ArchiveProvider -> Toast.makeText(
                                requireContext(),
                                "Internet Archive — puede incluir FLAC completo",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.onFailure { err ->
                    binding.emptyText.text = getString(R.string.lbl_spotiflac_error) + ": ${err.localizedMessage}"
                    binding.emptyText.isVisible = true
                }
            } catch (e: Throwable) {
                binding.searchProgress.isVisible = false
                binding.emptyText.text = "Error: ${e.localizedMessage}"
                binding.emptyText.isVisible = true
                android.util.Log.e("SpotiFlacFragment", "performSearch error", e)
            }
        }
    }

    private inner class TrackAdapter : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {
        private val items = mutableListOf<SpotiFlacDownloader.TrackMeta>()

        fun submitList(newItems: List<SpotiFlacDownloader.TrackMeta>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemSpotiflacTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class ViewHolder(private val b: ItemSpotiflacTrackBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(track: SpotiFlacDownloader.TrackMeta) {
                b.trackTitle.text = track.title
                b.trackArtist.text = "${track.artist} - ${track.album}"

                val badge = when {
                    track.isFreeDownload && track.isHiRes -> getString(R.string.lbl_spotiflac_hires)
                    track.isFreeDownload -> "CC Libre"
                    track.isHiRes -> getString(R.string.lbl_spotiflac_hires)
                    else -> getString(R.string.lbl_spotiflac_lossless)
                }
                b.badgeQuality.text = badge

                b.trackCover.setImageResource(R.drawable.ic_playing_indicator_24)
                b.btnDownload.isEnabled = true
                b.progressDownload.isVisible = false

                b.btnDownload.setOnClickListener {
                    b.btnDownload.isEnabled = false
                    b.progressDownload.isVisible = true
                    b.progressDownload.isIndeterminate = false
                    b.progressDownload.progress = 0

                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = downloader.downloadFlac(track) { pct ->
                            b.progressDownload.progress = pct
                        }
                        b.progressDownload.isVisible = false
                        b.btnDownload.isEnabled = true

                        result.onSuccess {
                            Toast.makeText(b.root.context, R.string.lbl_spotiflac_complete, Toast.LENGTH_LONG).show()
                        }.onFailure { err ->
                            Toast.makeText(b.root.context, "Error: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
