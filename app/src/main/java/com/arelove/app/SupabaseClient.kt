package com.arelove.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.net.URL
import java.net.HttpURLConnection

/**
 * Cliente único de Supabase para toda la aplicación.
 *
 * Las credenciales (SUPABASE_URL y SUPABASE_ANON_KEY) se inyectan en tiempo de
 * compilación desde app/secrets.properties a través de BuildConfig.
 * Nunca deben escribirse directamente en el código fuente.
 *
 * La anon key es pública por diseño: la seguridad la aplican las
 * políticas RLS de las tablas.
 */
object SupabaseClient {

    // Deep link de recuperación de contraseña. Tiene que estar en la lista de
    // Redirect URLs de Supabase (Authentication > URL Configuration); scheme y
    // host de aquí son los que instala(Auth) compara en handleDeeplinks.
    internal const val REDIRECT_RECUPERACION = "com.arelove.app://auth/reset"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
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
            install(Realtime) // chat en tiempo real (canal "messages:{chatId}")
        }
    }

    /**
     * Llama a una función RPC de Supabase vía REST API.
     * Endpoint: POST /rest/v1/rpc/{function_name}
     * Requiere headers: apikey, Authorization (Bearer anon key), Content-Type: application/json
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun rpc(
        functionName: String,
        params: JsonObject = buildJsonObject {},
        onResult: (JsonObject?) -> Unit,
        onError: (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/$functionName")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")

            val json = params.toString()
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