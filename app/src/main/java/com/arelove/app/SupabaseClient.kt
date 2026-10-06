package com.arelove.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URL
import java.net.HttpURLConnection

/**
 * Cliente único de Supabase para toda la aplicación.
 *
 * IMPORTANTE: reemplaza las dos constantes de abajo con los valores
 * reales de tu proyecto (Settings > API en el panel de Supabase).
 * La anon key es pública por diseño: la seguridad la aplican las
 * políticas RLS de las tablas.
 */
object SupabaseClient {

    // Visible fuera del objeto (PhotoUploadHelper.urlFirmada antepone el
    // dominio a la signed URL, que createSignedUrl devuelve relativa)
    internal const val SUPABASE_URL = "https://ggskodlzrkjbxilocyws.supabase.co"

    private const val SUPABASE_ANON_KEY = "sb_publishable_b7PwA4bBaiW6FEEonnmnIQ_AWsN6Bgv"

    // Deep link de recuperación de contraseña. Tiene que estar en la lista de
    // Redirect URLs de Supabase (Authentication > URL Configuration); scheme y
    // host de aquí son los que instala(Auth) compara en handleDeeplinks.
    internal const val REDIRECT_RECUPERACION = "com.arelove.app://auth/reset"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Auth) { // registro, login y verificación por correo
                // Guarda la sesión en SharedPreferences para sobrevivir reinicios
                sessionManager = SettingsSessionManager()
                // handleDeeplinks sólo actúa si el scheme y el host del Intent
                // coinciden con estos (com.arelove.app://auth/reset)
                scheme = "com.arelove.app"
                host = "auth"
            }
            install(Postgrest) // consultas a las tablas
            install(Storage) // fotos de perfil en el bucket "avatars"
            // Realtime se omita a proposito: el chat funciona con polling
            // cada 5 segundos (ChatActivity.suscribirRealtime). Se instalara
            // cuando se implemente de verdad el tiempo real (VUL-16).
        }
    }

    /**
     * Llama a una función RPC de Supabase vía REST API.
     * Endpoint: POST /rest/v1/rpc/{function_name}
     * Requiere headers: apikey, Authorization (Bearer anon key), Content-Type: application/json
     */
    @Suppress("UNUSED_PARAMETER")
    fun rpc(
        functionName: String,
        params: JsonObject = buildJsonObject {},
        onResult: (JsonObject?) -> Unit,
        onError: (Exception) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/rpc/$functionName")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                connection.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Prefer", "return=representation")

                val json = Json.encodeToString(params)
                connection.outputStream.use { it.write(json.toByteArray()) }

                val responseCode = connection.responseCode
                val inputStream = if (responseCode >= 400) connection.errorStream else connection.inputStream
                val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                connection.disconnect()

                if (responseCode >= 400) {
                    onError(Exception("RPC $functionName failed ($responseCode): $responseBody"))
                    return@withContext
                }

                if (responseBody.isBlank()) {
                    onResult(null)
                    return@withContext
                }

                val result = Json.decodeFromString<JsonObject>(responseBody)
                onResult(result)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
            install(Postgrest) // consultas a las tablas
            install(Storage) // fotos de perfil en el bucket "avatars"
            // Realtime se omita a proposito: el chat funciona con polling
            // cada 5 segundos (ChatActivity.suscribirRealtime). Se instalara
            // cuando se implemente de verdad el tiempo real (VUL-16).
        }
    }
}
