package org.oxycblt.auxio.downloader

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestor principal de descarga de musica para RealPlayerMusic (SpotiFLAC).
 *
 * Soporta fuentes de catalogo:
 *   - Spotify (API oficial, busqueda y playlists)
 *   - Deezer (30M canciones, FLAC con ARL)
 *   - ccMixter (Creative Commons libre)
 *   - Internet Archive (Conciertos live y FLAC)
 */
@Singleton
class SpotiFlacDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SpotiFlacDownloader"
        private const val PREFS = "spotiflac_prefs"
        private const val PREF_ARL = "deezer_arl"
        private const val PREF_PROVIDER = "active_provider"
        private const val PREF_SPOTIFY_CLIENT_ID = "spotify_client_id"
        private const val PREF_SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
        private const val PREF_YOUTUBE_API_KEY = "youtube_api_key"
    }

    data class TrackMeta(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String?,
        val previewUrl: String,
        val downloadUrl: String,
        val isHiRes: Boolean,
        val provider: String = "Deezer",
        val isFreeDownload: Boolean = false
    )

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // --- Deezer ARL ---
    var deezerArl: String
        get() = prefs.getString(PREF_ARL, "") ?: ""
        set(value) { prefs.edit().putString(PREF_ARL, value.trim()).apply() }

    val hasArl: Boolean get() = deezerArl.isNotEmpty()

    // --- Spotify Credentials ---
    var spotifyClientId: String
        get() = prefs.getString(PREF_SPOTIFY_CLIENT_ID, "") ?: ""
        set(value) { prefs.edit().putString(PREF_SPOTIFY_CLIENT_ID, value.trim()).apply() }

    var spotifyClientSecret: String
        get() = prefs.getString(PREF_SPOTIFY_CLIENT_SECRET, "") ?: ""
        set(value) { prefs.edit().putString(PREF_SPOTIFY_CLIENT_SECRET, value.trim()).apply() }

    val hasSpotifyCreds: Boolean
        get() = spotifyClientId.isNotEmpty() && spotifyClientSecret.isNotEmpty()

    // --- YouTube API Key (opcional) ---
    var youtubeApiKey: String
        get() = prefs.getString(PREF_YOUTUBE_API_KEY, "") ?: ""
        set(value) { prefs.edit().putString(PREF_YOUTUBE_API_KEY, value.trim()).apply() }

    // --- Active Provider ---
    var activeProviderKey: String
        get() = prefs.getString(PREF_PROVIDER, "deezer") ?: "deezer"
        set(value) { prefs.edit().putString(PREF_PROVIDER, value).apply() }

    /**
     * Descarga el track al directorio Music del dispositivo.
     */
    suspend fun downloadFlac(
        track: TrackMeta,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            var finalDownloadUrl = track.downloadUrl

            // Si es un track de Spotify sin stream directo, intentar resolver via Deezer
            if (finalDownloadUrl.isEmpty() && track.provider == "Spotify") {
                val resolved = resolveFromDeezer(track.title, track.artist)
                if (resolved != null) {
                    finalDownloadUrl = resolved
                }
            }

            if (finalDownloadUrl.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No hay URL de audio disponible para '${track.title}'. Configura tu ARL de Deezer o usa preview.")
                )
            }

            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val safeArtist = track.artist.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
            val safeTitle = track.title.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
            val targetDir = File(musicDir, safeArtist)
            if (!targetDir.exists()) targetDir.mkdirs()

            val ext = when {
                finalDownloadUrl.contains(".flac", ignoreCase = true) -> "flac"
                track.isHiRes && track.isFreeDownload -> "flac"
                else -> "mp3"
            }
            val mimeType = if (ext == "flac") "audio/flac" else "audio/mpeg"
            val targetFile = File(targetDir, "$safeTitle.$ext")

            downloadToFile(finalDownloadUrl, targetFile, onProgress)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType)
            ) { path, uri -> Log.i(TAG, "Escaneado $path -> $uri") }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error descargando", e)
            Result.failure(Exception(e.message ?: "Fallo de conexion al descargar archivo"))
        }
    }

    /**
     * Resuelve el enlace de audio para canciones de Spotify buscando en Deezer.
     */
    private fun resolveFromDeezer(title: String, artist: String): String? {
        return try {
            val q = URLEncoder.encode("$artist $title", "UTF-8")
            val url = URL("https://api.deezer.com/search?q=$q&limit=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    data.getJSONObject(0).optString("preview", null)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadToFile(
        downloadUrl: String,
        targetFile: File,
        onProgress: ((Int) -> Unit)?
    ) {
        val url = URL(downloadUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            connectTimeout = 20000
            readTimeout = 20000
            instanceFollowRedirects = true
        }
        val contentLength = conn.contentLength
        val input = conn.inputStream
        val output = FileOutputStream(targetFile)
        val buffer = ByteArray(8192)
        var totalRead = 0L
        try {
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (contentLength > 0 && onProgress != null) {
                    onProgress(((totalRead * 100) / contentLength).toInt())
                }
            }
        } finally {
            output.flush()
            output.close()
            input.close()
        }
    }
}
