package org.oxycblt.auxio.downloader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

    @Inject
    lateinit var downloader: SpotiFlacDownloader

    private val trackAdapter = TrackAdapter()

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentSpotiflacBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentSpotiflacBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.recyclerTracks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trackAdapter
        }

        binding.btnSearch.setOnClickListener {
            performSearch(binding)
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding)
                true
            } else {
                false
            }
        }
    }

    private fun performSearch(binding: FragmentSpotiflacBinding) {
        val query = binding.searchInput.text?.toString()?.trim() ?: return
        if (query.isEmpty()) return

        binding.searchProgress.isVisible = true
        binding.emptyText.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = downloader.searchAndResolve(query)
            binding.searchProgress.isVisible = false

            result.onSuccess { tracks ->
                if (tracks.isEmpty()) {
                    binding.emptyText.text = getString(R.string.lbl_spotiflac_empty)
                    binding.emptyText.isVisible = true
                    binding.recyclerTracks.isVisible = false
                } else {
                    binding.emptyText.isVisible = false
                    binding.recyclerTracks.isVisible = true
                    trackAdapter.submitList(tracks)
                }
            }.onFailure { err ->
                binding.emptyText.text = getString(R.string.lbl_spotiflac_error) + ": ${err.localizedMessage}"
                binding.emptyText.isVisible = true
                binding.recyclerTracks.isVisible = false
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
            val binding = ItemSpotiflacTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemSpotiflacTrackBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(track: SpotiFlacDownloader.TrackMeta) {
                binding.trackTitle.text = track.title
                binding.trackArtist.text = "${track.artist} - ${track.album}"
                binding.badgeQuality.text = if (track.isHiRes) {
                    binding.root.context.getString(R.string.lbl_spotiflac_hires)
                } else {
                    binding.root.context.getString(R.string.lbl_spotiflac_lossless)
                }

                // Use placeholder icon; full cover loading can be added later with coil-android
                binding.trackCover.setImageResource(R.drawable.ic_playing_indicator_24)

                binding.btnDownload.isEnabled = true
                binding.progressDownload.isVisible = false

                binding.btnDownload.setOnClickListener {
                    binding.btnDownload.isEnabled = false
                    binding.progressDownload.isVisible = true
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress = 0

                    viewLifecycleOwner.lifecycleScope.launch {
                        val dlResult = downloader.downloadFlac(track) { pct ->
                            binding.progressDownload.progress = pct
                        }

                        dlResult.onSuccess {
                            binding.progressDownload.isVisible = false
                            binding.btnDownload.isEnabled = true
                            Toast.makeText(
                                binding.root.context,
                                R.string.lbl_spotiflac_complete,
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { err ->
                            binding.progressDownload.isVisible = false
                            binding.btnDownload.isEnabled = true
                            Toast.makeText(
                                binding.root.context,
                                "Error: ${err.localizedMessage}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }
}
