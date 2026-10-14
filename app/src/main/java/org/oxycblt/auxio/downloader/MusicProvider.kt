package org.oxycblt.auxio.downloader

/**
 * Proveedor de musica descargable. Cada implementacion conecta con una fuente
 * diferente (Deezer, ccMixter, Archive.org, etc.).
 */
interface MusicProvider {
    /** Nombre visible al usuario */
    val name: String
    /** Descripcion corta del tipo de contenido */
    val description: String
    /** Icono de recurso drawable */
    val iconRes: Int
    /** Si requiere configuracion extra (ej. ARL) */
    val requiresConfig: Boolean

    /** Busca canciones que coincidan con [query]. */
    suspend fun search(query: String): Result<List<SpotiFlacDownloader.TrackMeta>>
}
