package org.oxycblt.auxio.downloader

import android.util.Log
import org.json.JSONArray
import org.oxycblt.auxio.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proveedor ccMixter:
 * Musica Creative Commons 100% gratuita y libre para descargar.
 * Todos los archivos son MP3 de descarga directa sin restricciones.
 */
@Singleton
class CcMixterProvider @Inject constructor() : MusicProvider {

    override val name = "ccMixter"
    override val description = "Musica Creative Commons | Descarga directa gratis | MP3"
    override val iconRes = R.drawable.ic_album_24
    override val requiresConfig = false

    companion object {
        private const val TAG = "CcMixterProvider"
        private const val API = "https://ccmixter.org/api/query"
    }

    override suspend fun search(query: String): Result<List<SpotiFlacDownloader.TrackMeta>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$API?search=$encoded&format=json&limit=20&lic=l")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AuxioHybrid/1.0")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) {
                return Result.failure(Exception("ccMixter HTTP ${conn.responseCode}"))
            }
            val text = conn.inputStream.bufferedReader().readText()
            if (text.isBlank()) return Result.success(emptyList())

            val items = JSONArray(text)
            val list = mutableListOf<SpotiFlacDownloader.TrackMeta>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val files = item.optJSONArray("files")
                val downloadUrl = if (files != null && files.length() > 0) {
                    files.getJSONObject(0).optString("download_url", "")
                } else ""

                if (downloadUrl.isEmpty()) continue

                list.add(SpotiFlacDownloader.TrackMeta(
                    id = item.optLong("upload_id", 0L),
                    title = item.optString("upload_name", "?"),
                    artist = item.optString("user_name", "?"),
                    album = "ccMixter",
                    durationMs = 0L,
                    coverUrl = item.optString("upload_extra", null)
                        ?.let { null }, // ccMixter no siempre incluye portadas
                    previewUrl = downloadUrl,
                    downloadUrl = downloadUrl,
                    isHiRes = false,
                    provider = "ccMixter",
                    isFreeDownload = true
                ))
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error en busqueda ccMixter", e)
            Result.failure(e)
        }
    }
}
