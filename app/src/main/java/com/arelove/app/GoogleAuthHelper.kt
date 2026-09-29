package com.arelove.app

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Auxiliar para obtener el ID Token de Google con Credential Manager
 * (ventana nativa de Google, sin salir de la app).
 */
object GoogleAuthHelper {

    // Web Client ID registrado en Google Cloud (cliente OAuth tipo Web)
    private const val WEB_CLIENT_ID =
        "607483592083-0fv7ivg4kqbdmjhda0auf6hidhefaa8d.apps.googleusercontent.com"

    /**
     * Muestra el selector nativo de cuentas de Google y devuelve el ID Token.
     *
     * - Lanza [Exception] con mensaje en español si el usuario cancela o si
     *   Credential Manager falla.
     * - Devuelve null si la credencial recibida no es la esperada.
     */
    suspend fun obtenerIdToken(context: Context): String? {
        // Opción: muestra todas las cuentas y autoselecciona si hay una sola
        val opcionGoogle = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

        // Petición con la única opción: Google
        val peticion = GetCredentialRequest.Builder()
            .addCredentialOption(opcionGoogle)
            .build()

        val gestor = CredentialManager.create(context)

        val respuesta = try {
            gestor.getCredential(context, peticion)
        } catch (e: GetCredentialCancellationException) {
            throw Exception(context.getString(R.string.error_login_cancelled))
        } catch (e: GetCredentialException) {
            throw Exception(context.getString(R.string.error_google_picker_failed))
        }

        // Extraer el ID Token solo si la credencial es de tipo Google
        val credencial = respuesta.credential
        if (credencial is CustomCredential &&
            credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                GoogleIdTokenCredential.createFrom(credencial.data).idToken
            } catch (e: Exception) {
                null // credencial mal formada → sin token
            }
        }
        return null
    }
}
