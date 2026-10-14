package org.oxycblt.auxio.downloader

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import org.oxycblt.auxio.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proveedor Deezer:
 * - Busqueda gratuita sin autenticacion via API publica de Deezer.
 * - Preview 30s MP3 gratis para todos.
 * - Descarga FLAC completa con ARL de Deezer Premium (opcional).
 */
@Singleton
class DeezerProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : MusicProvider {

    override val name = "Deezer"
    override val description = "30M canciones | Preview gratis | FLAC con cuenta Premium"
    override val iconRes = R.drawable.ic_album_24
    override val requiresConfig = false

    companion object {
        private const val TAG = "DeezerProvider"
        private const val API = "https://api.deezer.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun search(query: String): Result<List<SpotiFlacDownloader.TrackMeta>> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("$API/search?q=$encoded&limit=25&output=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 12000
                readTimeout = 12000
            }
            if (conn.responseCode != 200) {
                return Result.failure(Exception("Deezer respondio HTTP ${conn.responseCode}"))
            }

            val rawJson = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(rawJson)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "Error en la API de Deezer") ?: "Error en Deezer"
                return Result.failure(Exception(errMsg))
            }

            val items = json.optJSONArray("data") ?: return Result.success(emptyList())

            val list = mutableListOf<SpotiFlacDownloader.TrackMeta>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val album = item.optJSONObject("album")
                val artist = item.optJSONObject("artist")
                val preview = item.optString("preview", "")

                list.add(SpotiFlacDownloader.TrackMeta(
                    id = item.optLong("id", 0L),
                    title = item.optString("title", "?"),
                    artist = artist?.optString("name", "?") ?: "?",
                    album = album?.optString("title", "?") ?: "?",
                    durationMs = item.optLong("duration", 0L) * 1000,
                    coverUrl = album?.optString("cover_medium"),
                    previewUrl = preview,
                    downloadUrl = preview,
                    isHiRes = false,
                    provider = "Deezer",
                    isFreeDownload = false
                ))
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error en busqueda Deezer", e)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Result.failure(Exception("Deezer: $msg"))
        }
    }
}
