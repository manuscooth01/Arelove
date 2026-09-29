package com.arelove.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Lectura, subida y borrado de las fotos subidas por la app:
 * las fotos de perfil (paso 3, bucket "avatars") y la selfie de
 * verificación (paso 5, bucket "verification").
 *
 * Todas las operaciones de red y de disco se hacen en [Dispatchers.IO];
 * el resto de la app solo espera el resultado (bytes leídos, URL pública
 * o true/false del borrado).
 */
object PhotoUploadHelper {

    /** Etiqueta para el log de errores. */
    private const val TAG_LOG = "PhotoUploadHelper"

    /** Bucket por defecto de Supabase Storage: las fotos de perfil. */
    private const val BUCKET = "avatars"

    /** Ancho máximo de la foto que se sube (en píxeles). */
    private const val ANCHO_MAXIMO = 1080

    /** Calidad del JPEG que se sube (100 = sin pérdida). */
    private const val CALIDAD_JPEG = 85

    /**
     * Lee la imagen del [uri] y la devuelve como JPEG ya reescalado.
     *
     * 1. Decodifica solo los límites para saber el tamaño original.
     * 2. Decodifica con `inSampleSize` para no cargar el archivo entero en RAM.
     * 3. Escala a lo ancho máximo y comprime en JPEG en memoria.
     *
     * @return los bytes del JPEG o null si la imagen no se pudo leer ni recodificar
     */
    suspend fun recomprimir(context: Context, uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                // 1) Pasada sin decodificar: solo escribe outWidth/outHeight
                val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { entrada ->
                    BitmapFactory.decodeStream(entrada, null, limites)
                }
                if (limites.outWidth <= 0 || limites.outHeight <= 0) {
                    Log.e(TAG_LOG, "La imagen no tiene dimensiones válidas: $uri")
                    return@withContext null
                }

                // 2) Decodificado reducido
                val opciones = BitmapFactory.Options().apply {
                    inSampleSize = calcularMuestra(limites.outWidth)
                }
                val original = context.contentResolver.openInputStream(uri)?.use { entrada ->
                    BitmapFactory.decodeStream(entrada, null, opciones)
                }
                if (original == null) {
                    Log.e(TAG_LOG, "No se pudo decodificar la imagen: $uri")
                    return@withContext null
                }

                // 3) Escalado final y compresión
                val ajustada = escalar(original, ANCHO_MAXIMO)
                val bytes = ByteArrayOutputStream().use { salida ->
                    ajustada.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, salida)
                    salida.toByteArray()
                }

                if (ajustada !== original) original.recycle()
                ajustada.recycle()
                bytes
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error recodificando la foto", e)
                null
            }
        }

    /**
     * Sube los [bytes] al bucket indicado y devuelve la URL pública.
     *
     * @param userId id del usuario; la foto se guarda en `{userId}/{archivo}`
     * @param bucket bucket de Supabase Storage donde se guarda la foto
     *   (por defecto "avatars"; el paso 5 usa "verification")
     * @return la URL pública o null si la subida falló
     */
    suspend fun subirFoto(userId: String, bytes: ByteArray, bucket: String = BUCKET): String? =
        withContext(Dispatchers.IO) {
            try {
                val nombre = "${System.currentTimeMillis()}_${(1000..9999).random()}.jpg"
                val path = "$userId/$nombre"
                val almacen = SupabaseClient.client.storage[bucket]
                almacen.upload(path, bytes)
                val url = almacen.publicUrl(path)
                Log.d(TAG_LOG, "Foto subida, URL: $url")
                url
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error subiendo la foto", e)
                null
            }
        }

    /**
     * Devuelve una URL firmada temporal (1 hora) para un archivo de un
     * bucket privado. Se usa para previews de verificación: el bucket
     * "verification" es privado, así que su URL pública no se puede cargar.
     *
     * @param bucket bucket privado donde está el archivo
     * @param path ruta interna dentro del bucket (`{userId}/{archivo}`)
     * @return la URL firmada o null si la petición falló
     */
    suspend fun urlFirmada(bucket: String, path: String): String? =
        withContext(Dispatchers.IO) {
            try {
                Log.e(TAG_LOG, "urlFirmada(bucket=$bucket, path=$path)")
                SupabaseClient.client.storage[bucket]
                    .createSignedUrl(path, expiresIn = 3600.seconds)
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error creando signed URL: ${e.message}", e)
                null
            }
        }

    /**
     * Calcula el SHA-256 de [bytes] y lo devuelve en hexadecimal.
     * Sirve para detectar fotos ya subidas (mismo contenido = mismo hash).
     */
    fun hashBytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Borra el archivo del bucket a partir de su URL pública.
     *
     * @param bucket bucket donde está el archivo (por defecto "avatars")
     * @return true si el archivo se borró, false si la URL no se reconoce o falla la petición
     */
    suspend fun borrarFoto(url: String, bucket: String = BUCKET): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val path = extraerPath(url, bucket)
                if (path == null) {
                    Log.e(TAG_LOG, "No se reconoce la ruta de la foto: $url")
                    return@withContext false
                }
                SupabaseClient.client.storage[bucket].delete(path)
                true
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error borrando la foto", e)
                false
            }
        }

    /**
     * Extrae la ruta interna (`{userId}/{archivo}`) de una URL pública.
     * Ejemplo: `.../object/public/avatars/abc/1.jpg` -> `abc/1.jpg`.
     */
    private fun extraerPath(url: String, bucket: String): String? {
        val marca = "/object/public/$bucket/"
        val indice = url.indexOf(marca)
        if (indice < 0) return null
        return url.substring(indice + marca.length).ifEmpty { null }
    }

    /** Muestra de decodificación (potencia de 2) para no exceder [ANCHO_MAXIMO]. */
    private fun calcularMuestra(ancho: Int): Int {
        var muestra = 1
        while (ancho / muestra > ANCHO_MAXIMO && muestra < 64) {
            muestra *= 2
        }
        return muestra
    }

    /** Devuelve la misma imagen si ya cabe en [anchoMax], o una escalada. */
    private fun escalar(bitmap: Bitmap, anchoMax: Int): Bitmap {
        if (bitmap.width <= anchoMax) return bitmap
        val alto = (anchoMax.toLong() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, anchoMax, alto, true)
    }
}
