package org.oxycblt.auxio.downloader

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestor principal de descarga de musica para SpotiFLAC.
 *
 * Proveedores integrados (gratuitos):
 *   1. Deezer     — 30M canciones, preview 30s MP3. FLAC con ARL Premium.
 *   2. ccMixter   — Musica Creative Commons, descarga directa MP3 libre.
 *   3. Archive.org — Conciertos live, clasicos, dominio publico. Incluye FLAC.
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

    var deezerArl: String
        get() = prefs.getString(PREF_ARL, "") ?: ""
        set(value) { prefs.edit().putString(PREF_ARL, value.trim()).apply() }

    val hasArl: Boolean get() = deezerArl.isNotEmpty()

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
            if (track.downloadUrl.isEmpty()) {
                return@withContext Result.failure(Exception("URL de descarga no disponible"))
            }
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val safeArtist = track.artist.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
            val safeTitle = track.title.replace("[/\\\\?%*:|\"<>]".toRegex(), "_")
            val targetDir = File(musicDir, safeArtist)
            if (!targetDir.exists()) targetDir.mkdirs()

            val ext = when {
                track.downloadUrl.contains(".flac", ignoreCase = true) -> "flac"
                track.isHiRes && track.isFreeDownload -> "flac"
                else -> "mp3"
            }
            val mimeType = if (ext == "flac") "audio/flac" else "audio/mpeg"
            val targetFile = File(targetDir, "$safeTitle.$ext")

            downloadToFile(track.downloadUrl, targetFile, onProgress)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType)
            ) { path, uri -> Log.i(TAG, "Escaneado $path -> $uri") }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error descargando", e)
            Result.failure(e)
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
            setRequestProperty("User-Agent", "AuxioHybrid/1.0")
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
