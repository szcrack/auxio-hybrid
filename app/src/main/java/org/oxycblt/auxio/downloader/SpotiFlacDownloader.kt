package org.oxycblt.auxio.downloader

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SpotiFLAC downloader client:
 * Resolves lossless FLAC streams (16-bit / 24-bit) from hi-fi sources,
 * downloads them directly into the device's Music folder,
 * and notifies Android's MediaScanner so Auxio indexes them immediately.
 */
class SpotiFlacDownloader(private val context: Context) {

    companion object {
        private const val TAG = "SpotiFlacDownloader"
        private const val USER_AGENT = "SpotiFLAC-Android/1.0"
        // Public SpotiFLAC resolver endpoint
        private const val RESOLVER_API = "https://api.spotiflac.com/v1"
    }

    data class TrackMeta(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String?,
        val downloadUrl: String,
        val isHiRes: Boolean
    )

    /**
     * Resolves a query or Spotify link into a downloadable lossless FLAC stream.
     */
    suspend fun searchAndResolve(query: String): Result<List<TrackMeta>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("$RESOLVER_API/search?q=$encodedQuery&format=flac")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (connection.responseCode != 200) {
                return@withContext Result.failure(Exception("API returned HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val items = json.optJSONArray("tracks") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<TrackMeta>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                list.add(
                    TrackMeta(
                        title = item.optString("title", "Unknown Title"),
                        artist = item.optString("artist", "Unknown Artist"),
                        album = item.optString("album", "Unknown Album"),
                        durationMs = item.optLong("duration_ms", 0L),
                        coverUrl = item.optString("cover_url", null),
                        downloadUrl = item.optString("download_url", ""),
                        isHiRes = item.optBoolean("hi_res", false)
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve track", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a FLAC track to the user's Music directory and triggers indexing.
     */
    suspend fun downloadFlac(
        track: TrackMeta,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val safeArtist = track.artist.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
            val safeTitle = track.title.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
            val targetDir = File(musicDir, safeArtist)
            if (!targetDir.exists()) targetDir.mkdirs()

            val targetFile = File(targetDir, "$safeTitle.flac")

            val url = URL(track.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 15000
                readTimeout = 15000
            }

            val contentLength = connection.contentLength
            val input = connection.inputStream
            val output = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (contentLength > 0 && onProgress != null) {
                    val progress = ((totalRead * 100) / contentLength).toInt()
                    onProgress(progress)
                }
            }

            output.flush()
            output.close()
            input.close()

            // Trigger MediaScanner so Auxio Room database detects the new song instantly
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("audio/flac")
            ) { path, uri ->
                Log.i(TAG, "Scanned $path into Android MediaStore: $uri")
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading track", e)
            Result.failure(e)
        }
    }
}
