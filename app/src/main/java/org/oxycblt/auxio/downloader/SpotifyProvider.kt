package org.oxycblt.auxio.downloader

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.oxycblt.auxio.R
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: SpotiFlacDownloader
) : MusicProvider {

    override val name = "Spotify"
    override val description = "Catálogo oficial y playlists de Spotify | Requiere credenciales gratis"
    override val iconRes = R.drawable.ic_song_24
    override val requiresConfig = true

    companion object {
        private const val TAG = "SpotifyProvider"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SEARCH_URL = "https://api.spotify.com/v1/search"
        private const val PLAYLIST_URL = "https://api.spotify.com/v1/playlists"
    }

    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0L

    private suspend fun getAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val clientId = downloader.spotifyClientId.trim()
        val clientSecret = downloader.spotifyClientSecret.trim()

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            return@withContext Result.failure(
                Exception("Faltan credenciales de Spotify. Toca 'Cuentas' para ingresar tu Client ID y Client Secret.")
            )
        }

        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryTime) {
            return@withContext Result.success(cachedToken!!)
        }

        try {
            val url = URL(TOKEN_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val basicAuth = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $basicAuth")
                connectTimeout = 10000
                readTimeout = 10000
            }

            val body = "grant_type=client_credentials"
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode != 200) {
                val errText = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return@withContext Result.failure(Exception("Error de autenticación con Spotify ($errText)"))
            }

            val resp = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(resp)
            val token = json.getString("access_token")
            val expiresInSec = json.optLong("expires_in", 3600L)

            cachedToken = token
            tokenExpiryTime = System.currentTimeMillis() + (expiresInSec - 60) * 1000

            Result.success(token)
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo token de Spotify", e)
            Result.failure(Exception(e.message ?: "Fallo de conexión con Spotify"))
        }
    }

    override suspend fun search(query: String): Result<List<SpotiFlacDownloader.TrackMeta>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()

        if (trimmed.contains("spotify.com/playlist/") || trimmed.contains("spotify:playlist:")) {
            val playlistId = extractPlaylistId(trimmed)
            if (playlistId.isNotEmpty()) {
                return@withContext loadPlaylistTracks(playlistId)
            }
        }

        val tokenResult = getAccessToken()
        if (tokenResult.isFailure) {
            return@withContext Result.failure(tokenResult.exceptionOrNull()!!)
        }
        val token = tokenResult.getOrThrow()

        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = URL("$SEARCH_URL?q=$encoded&type=track&limit=25")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "RVPLAYER/1.0")
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (conn.responseCode != 200) {
                val errText = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return@withContext Result.failure(Exception("Spotify error: $errText"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tracksObj = json.optJSONObject("tracks")
            val items = tracksObj?.optJSONArray("items") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<SpotiFlacDownloader.TrackMeta>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val albumObj = item.optJSONObject("album")
                val artistsArr = item.optJSONArray("artists")
                val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                    artistsArr.getJSONObject(0).optString("name", "Desconocido")
                } else "Desconocido"

                val images = albumObj?.optJSONArray("images")
                val coverUrl = if (images != null && images.length() > 0) {
                    images.getJSONObject(0).optString("url")
                } else null

                val title = item.optString("name", "Desconocido")
                val previewUrl = item.optString("preview_url", "")

                list.add(
                    SpotiFlacDownloader.TrackMeta(
                        id = item.optString("id").hashCode().toLong(),
                        title = title,
                        artist = artistName,
                        album = albumObj?.optString("name", "Desconocido") ?: "Desconocido",
                        durationMs = item.optLong("duration_ms", 0L),
                        coverUrl = coverUrl,
                        previewUrl = previewUrl,
                        downloadUrl = previewUrl,
                        isHiRes = true,
                        provider = "Spotify",
                        isFreeDownload = false
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando en Spotify", e)
            Result.failure(Exception(e.message ?: "Error al buscar en Spotify"))
        }
    }

    private suspend fun loadPlaylistTracks(playlistId: String): Result<List<SpotiFlacDownloader.TrackMeta>> = withContext(Dispatchers.IO) {
        val tokenResult = getAccessToken()
        if (tokenResult.isFailure) return@withContext Result.failure(tokenResult.exceptionOrNull()!!)
        val token = tokenResult.getOrThrow()

        try {
            val url = URL("$PLAYLIST_URL/$playlistId/tracks?limit=50")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 12000
                readTimeout = 12000
            }

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("No se pudo cargar la playlist (HTTP ${conn.responseCode}). Asegúrate de que sea pública."))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val items = json.optJSONArray("items") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<SpotiFlacDownloader.TrackMeta>()
            for (i in 0 until items.length()) {
                val trackObj = items.getJSONObject(i).optJSONObject("track") ?: continue
                val albumObj = trackObj.optJSONObject("album")
                val artistsArr = trackObj.optJSONArray("artists")
                val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                    artistsArr.getJSONObject(0).optString("name", "Desconocido")
                } else "Desconocido"

                val images = albumObj?.optJSONArray("images")
                val coverUrl = if (images != null && images.length() > 0) {
                    images.getJSONObject(0).optString("url")
                } else null

                list.add(
                    SpotiFlacDownloader.TrackMeta(
                        id = trackObj.optString("id").hashCode().toLong(),
                        title = trackObj.optString("name", "Desconocido"),
                        artist = artistName,
                        album = albumObj?.optString("name", "Spotify Playlist") ?: "Spotify Playlist",
                        durationMs = trackObj.optLong("duration_ms", 0L),
                        coverUrl = coverUrl,
                        previewUrl = trackObj.optString("preview_url", ""),
                        downloadUrl = trackObj.optString("preview_url", ""),
                        isHiRes = true,
                        provider = "Spotify",
                        isFreeDownload = false
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando playlist", e)
            Result.failure(Exception(e.message ?: "Error al cargar canciones de la playlist"))
        }
    }

    private fun extractPlaylistId(urlOrId: String): String {
        return try {
            if (urlOrId.contains("playlist/")) {
                val after = urlOrId.substringAfter("playlist/")
                after.substringBefore("?").substringBefore("/")
            } else if (urlOrId.contains("playlist:")) {
                urlOrId.substringAfter("playlist:")
            } else {
                urlOrId.trim()
            }
        } catch (_: Exception) {
            ""
        }
    }
}
