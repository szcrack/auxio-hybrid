package org.oxycblt.auxio.downloader

import android.util.Log
import org.json.JSONObject
import org.oxycblt.auxio.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proveedor Internet Archive (archive.org):
 * Miles de grabaciones en vivo, albums clasicos y musica libre.
 * Incluye archivos FLAC/MP3 de descarga directa y gratuita.
 */
@Singleton
class ArchiveProvider @Inject constructor() : MusicProvider {

    override val name = "Internet Archive"
    override val description = "Conciertos en vivo + clasicos | FLAC gratis | Dominio publico"
    override val iconRes = R.drawable.ic_album_24
    override val requiresConfig = false

    companion object {
        private const val TAG = "ArchiveProvider"
        private const val SEARCH = "https://archive.org/advancedsearch.php"
        private const val FILES = "https://archive.org/metadata"
    }

    override suspend fun search(query: String): Result<List<SpotiFlacDownloader.TrackMeta>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$SEARCH?q=${encoded}+mediatype%3Aaudio&fl[]=identifier,title,creator,description&rows=15&output=json&sort[]=downloads+desc"
            val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AuxioHybrid/1.0")
                connectTimeout = 12000
                readTimeout = 12000
            }
            if (conn.responseCode != 200) {
                return Result.failure(Exception("Archive.org HTTP ${conn.responseCode}"))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val docs = json.optJSONObject("response")?.optJSONArray("docs")
                ?: return Result.success(emptyList())

            val list = mutableListOf<SpotiFlacDownloader.TrackMeta>()
            for (i in 0 until minOf(docs.length(), 10)) {
                val doc = docs.getJSONObject(i)
                val identifier = doc.optString("identifier", "") ?: continue
                if (identifier.isEmpty()) continue

                val title = doc.optString("title", identifier)
                val creator = when (val c = doc.opt("creator")) {
                    is String -> c
                    is org.json.JSONArray -> if (c.length() > 0) c.getString(0) else "Desconocido"
                    else -> "Desconocido"
                }

                // URL directa de descarga del primer archivo audio
                val downloadUrl = "https://archive.org/download/$identifier"

                list.add(SpotiFlacDownloader.TrackMeta(
                    id = identifier.hashCode().toLong(),
                    title = title,
                    artist = creator,
                    album = "Internet Archive",
                    durationMs = 0L,
                    coverUrl = "https://archive.org/services/img/$identifier",
                    previewUrl = downloadUrl,
                    downloadUrl = downloadUrl,
                    isHiRes = true,  // muchos son FLAC
                    provider = "Archive.org",
                    isFreeDownload = true
                ))
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error en busqueda Archive.org", e)
            Result.failure(e)
        }
    }
}
