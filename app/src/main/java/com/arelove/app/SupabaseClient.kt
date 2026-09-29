package com.arelove.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * Cliente único de Supabase para toda la aplicación.
 *
 * IMPORTANTE: reemplaza las dos constantes de abajo con los valores
 * reales de tu proyecto (Settings > API en el panel de Supabase).
 * La anon key es pública por diseño: la seguridad la aplican las
 * políticas RLS de las tablas.
 */
object SupabaseClient {

    private const val SUPABASE_URL = "https://ggskodlzrkjbxilocyws.supabase.co"

    private const val SUPABASE_ANON_KEY = "sb_publishable_b7PwA4bBaiW6FEEonnmnIQ_AWsN6Bgv"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Auth) { // registro, login y verificación por correo
                // Guarda la sesión en SharedPreferences para sobrevivir reinicios
                sessionManager = SettingsSessionManager()
            }
            install(Postgrest) // consultas a las tablas
            install(Storage) // fotos de perfil en el bucket "avatars"
        }
    }
}
