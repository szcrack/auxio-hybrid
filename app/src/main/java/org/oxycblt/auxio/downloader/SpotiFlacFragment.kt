package org.oxycblt.auxio.downloader

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
    @Inject lateinit var spotifyProvider: SpotifyProvider
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
            // Proveedor activo inicial
            activeProvider = when (downloader.activeProviderKey) {
                "spotify" -> spotifyProvider
                "ccmixter" -> ccMixterProvider
                "archive" -> archiveProvider
                else -> deezerProvider
            }

            updateProviderChip(binding)

            binding.recyclerTracks.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = trackAdapter
            }

            binding.btnSearch.setOnClickListener { performSearch(binding) }

            binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    performSearch(binding)
                    true
                } else false
            }

            // Seleccionar proveedor
            binding.chipProvider.setOnClickListener {
                showProviderDialog(binding)
            }

            // Botón de Cuentas / Credenciales
            binding.btnAccounts.setOnClickListener {
                showAccountsDialog(binding)
            }

        } catch (e: Throwable) {
            android.util.Log.e("SpotiFlacFragment", "onBindingCreated failed", e)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            binding.emptyText.text = "Error al inicializar: $msg"
            binding.emptyText.isVisible = true
        }
    }

    private fun updateProviderChip(binding: FragmentSpotiflacBinding) {
        val provider = activeProvider ?: deezerProvider
        binding.chipProvider.text = provider.name
        binding.txtProviderDesc.text = provider.description
    }

    private fun showProviderDialog(binding: FragmentSpotiflacBinding) {
        val providers = listOf(
            "deezer" to "Deezer — 30M canciones (Preview gratis / FLAC con cuenta)",
            "spotify" to "Spotify — Catálogo oficial y Playlists (Requiere credenciales)",
            "ccmixter" to "ccMixter — Música Creative Commons (Descarga libre)",
            "archive" to "Internet Archive — Conciertos live + Clásicos FLAC"
        )
        val names = providers.map { it.second }.toTypedArray()
        val currentIdx = providers.indexOfFirst { it.first == downloader.activeProviderKey }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar fuente musical")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val key = providers[which].first
                downloader.activeProviderKey = key
                activeProvider = when (key) {
                    "spotify" -> spotifyProvider
                    "ccmixter" -> ccMixterProvider
                    "archive" -> archiveProvider
                    else -> deezerProvider
                }
                updateProviderChip(binding)
                dialog.dismiss()

                // Si seleccionó Spotify y no tiene credenciales, invitar a configurarlas
                if (key == "spotify" && !downloader.hasSpotifyCreds) {
                    Toast.makeText(
                        requireContext(),
                        "Toca 'Cuentas' para agregar tus credenciales de Spotify",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(requireContext(), "Fuente: ${activeProvider?.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Diálogo unificado para configurar credenciales de Spotify, Deezer y YouTube.
     */
    private fun showAccountsDialog(binding: FragmentSpotiflacBinding) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // Spotify Section
        val lblSpotify = TextView(ctx).apply {
            text = "Spotify Web API (developer.spotify.com)"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }
        val edtClientId = EditText(ctx).apply {
            hint = "Spotify Client ID"
            setText(downloader.spotifyClientId)
            maxLines = 1
        }
        val edtClientSecret = EditText(ctx).apply {
            hint = "Spotify Client Secret"
            setText(downloader.spotifyClientSecret)
            maxLines = 1
        }

        // Deezer Section
        val lblDeezer = TextView(ctx).apply {
            text = "Deezer ARL (Cookie de sesión para FLAC)"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        val edtArl = EditText(ctx).apply {
            hint = "Pega tu cookie ARL de Deezer"
            setText(downloader.deezerArl)
        }

        // YouTube Section
        val lblYt = TextView(ctx).apply {
            text = "YouTube Data API Key (Opcional)"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        val edtYtKey = EditText(ctx).apply {
            hint = "YouTube API Key (Google Cloud)"
            setText(downloader.youtubeApiKey)
        }

        layout.addView(lblSpotify)
        layout.addView(edtClientId)
        layout.addView(edtClientSecret)
        layout.addView(lblDeezer)
        layout.addView(edtArl)
        layout.addView(lblYt)
        layout.addView(edtYtKey)

        AlertDialog.Builder(ctx)
            .setTitle("Credenciales y Cuentas")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                downloader.spotifyClientId = edtClientId.text.toString()
                downloader.spotifyClientSecret = edtClientSecret.text.toString()
                downloader.deezerArl = edtArl.text.toString()
                downloader.youtubeApiKey = edtYtKey.text.toString()
                Toast.makeText(ctx, "Credenciales guardadas con éxito", Toast.LENGTH_SHORT).show()
                updateProviderChip(binding)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performSearch(binding: FragmentSpotiflacBinding) {
        val query = binding.searchInput.text?.toString()?.trim() ?: return
        if (query.isEmpty()) return

        // Auto-detección: si pega un link de Spotify, cambiar automáticamente a Spotify
        if (query.contains("spotify.com/") || query.contains("spotify:")) {
            activeProvider = spotifyProvider
            downloader.activeProviderKey = "spotify"
            updateProviderChip(binding)
        }

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
                        binding.emptyText.text = "No se encontraron canciones para '$query'. Prueba con otro término o cambia de fuente."
                        binding.emptyText.isVisible = true
                    } else {
                        binding.recyclerTracks.isVisible = true
                        trackAdapter.submitList(tracks)
                    }
                }.onFailure { err ->
                    val errorMsg = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                    binding.emptyText.text = "Error al buscar: $errorMsg"
                    binding.emptyText.isVisible = true
                }
            } catch (e: Throwable) {
                binding.searchProgress.isVisible = false
                val errorMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                binding.emptyText.text = "Error de red: $errorMsg"
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
                b.trackArtist.text = "${track.artist} • ${track.album}"

                val badge = when {
                    track.provider == "Spotify" -> "Spotify"
                    track.isFreeDownload && track.isHiRes -> "FLAC Libre"
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
                            val msg = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                            Toast.makeText(b.root.context, "Error: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
