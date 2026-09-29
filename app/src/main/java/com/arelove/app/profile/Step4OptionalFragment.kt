package com.arelove.app.profile

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.ErrorSanitizer
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentStep4OptionalBinding
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Paso 4 del asistente de perfil: datos opcionales.
 *
 * Biografía, altura, peso, ocupación, música favorita y signo zodiacal.
 * Todo es opcional, así que solo se validan los rangos de altura (100-250 cm)
 * y peso (30-300 kg) cuando traen valor. El guardado es un UPSERT en
 * `profiles` con id = auth.uid(); los campos vacíos se envían como JsonNull
 * para poder borrar valores guardados en una visita anterior.
 */
class Step4OptionalFragment : Fragment() {

    private var _binding: FragmentStep4OptionalBinding? = null
    private val binding get() = _binding!!

    /** Evita guardar dos veces si pulsan "Siguiente" repetidamente. */
    private var guardando = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep4OptionalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configurarCampos()
        cargarDatosExistentes()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Desplegable de signos + errores en línea que se limpian al escribir. */
    private fun configurarCampos() {
        binding.etZodiac.setSimpleItems(resources.getStringArray(R.array.zodiac_signs))

        binding.etBio.doAfterTextChanged { _binding?.tilBio?.error = null }
        binding.etHeight.doAfterTextChanged { _binding?.tilHeight?.error = null }
        binding.etWeight.doAfterTextChanged { _binding?.tilWeight?.error = null }
        binding.etOccupation.doAfterTextChanged { _binding?.tilOccupation?.error = null }
        binding.etFavoriteMusic.doAfterTextChanged { _binding?.tilFavoriteMusic?.error = null }
        binding.etZodiac.doAfterTextChanged { _binding?.tilZodiac?.error = null }
    }

    // ---------------------------------------------------------------------
    // Carga de los datos ya guardados
    // ---------------------------------------------------------------------

    /**
     * SELECT bio, height, weight, zodiac, occupation, favorite_music
     *     FROM profiles WHERE id = userId LIMIT 1
     *
     * Los campos con null en Supabase se dejan vacíos en el formulario.
     */
    private fun cargarDatosExistentes() {
        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina (nunca en el hilo principal)
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                    ?: return@launch

                val fila = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list(
                        "bio", "height", "weight", "zodiac", "occupation", "favorite_music"
                    )
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeSingleOrNull<JsonObject>() ?: return@launch

                val b = _binding ?: return@launch
                b.etBio.setText(textoDe(fila, "bio"))
                b.etHeight.setText(textoDe(fila, "height"))
                b.etWeight.setText(textoDe(fila, "weight"))
                // setText(valor, false) para no disparar el filtrado del desplegable
                b.etZodiac.setText(textoDe(fila, "zodiac"), false)
                b.etOccupation.setText(textoDe(fila, "occupation"))
                b.etFavoriteMusic.setText(textoDe(fila, "favorite_music"))
            } catch (e: Exception) {
                // Sin conexión o sin permiso: el paso es opcional, se sigue con el formulario vacío
                Log.e(TAG_LOG, "Error cargando el perfil opcional", e)
            }
        }
    }

    /**
     * Devuelve el valor de [clave] como texto para pintarlo en el campo;
     * null y columnas ausentes se traducen en cadena vacía.
     */
    private fun textoDe(fila: JsonObject, clave: String): String {
        val elemento = fila[clave] as? JsonPrimitive ?: return ""
        if (elemento is JsonNull) return ""
        return elemento.content.trim()
    }

    // ---------------------------------------------------------------------
    // Validación y guardado
    // ---------------------------------------------------------------------

    /**
     * Valida los rangos opcionales de altura y peso y, si todo está correcto,
     * hace un UPSERT en la tabla `profiles` con id = auth.uid(). Los campos
     * vacíos se envían como JsonNull (para poder borrar un valor previo).
     *
     * @param onSuccess se llama cuando el guardado termina bien (para avanzar de paso)
     * @param onError se llama con el mensaje de error a mostrar
     */
    fun validarYGuardar(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (guardando) return
        val b = _binding ?: return

        // Limpiamos los errores de la validación anterior
        b.tilHeight.error = null
        b.tilWeight.error = null

        val bio = b.etBio.text?.toString()?.trim().orEmpty()
        val alturaTexto = b.etHeight.text?.toString()?.trim().orEmpty()
        val pesoTexto = b.etWeight.text?.toString()?.trim().orEmpty()
        val ocupacion = b.etOccupation.text?.toString()?.trim().orEmpty()
        val musica = b.etFavoriteMusic.text?.toString()?.trim().orEmpty()
        val signo = b.etZodiac.text?.toString()?.trim().orEmpty()

        // Altura opcional: si tiene valor, debe estar entre 100 y 250 cm
        val altura = if (alturaTexto.isEmpty()) null else alturaTexto.toIntOrNull()
        if (alturaTexto.isNotEmpty() && (altura == null || altura !in ALTURA_MINIMA..ALTURA_MAXIMA)) {
            b.tilHeight.error = getString(R.string.validation_height_range)
            return
        }

        // Peso opcional: si tiene valor, debe estar entre 30 y 300 kg
        val peso = if (pesoTexto.isEmpty()) null else pesoTexto.toIntOrNull()
        if (pesoTexto.isNotEmpty() && (peso == null || peso !in PESO_MINIMO..PESO_MAXIMO)) {
            b.tilWeight.error = getString(R.string.validation_weight_range)
            return
        }

        guardando = true

        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina: en el hilo principal
                // currentSessionOrNull() puede bloquear y congelar la pantalla
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                if (userId == null) {
                    onError(getString(R.string.profile_error_no_session))
                    return@launch
                }

                val fila = buildJsonObject {
                    put("id", userId)
                    ponerTexto("bio", bio)
                    ponerNumero("height", altura)
                    ponerNumero("weight", peso)
                    ponerTexto("zodiac", signo)
                    ponerTexto("occupation", ocupacion)
                    ponerTexto("favorite_music", musica)
                }
                // UPSERT: crea la fila si no existe y la actualiza si ya existe
                SupabaseClient.client.postgrest["profiles"].upsert(fila)
                onSuccess()
            } catch (e: Exception) {
                // Stacktrace completo en logcat para diagnosticar la causa real
                Log.e(TAG_LOG, "Error guardando el perfil opcional", e)
                onError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.profile_error_save),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                // Siempre se libera el bloqueo para poder reintentar
                guardando = false
            }
        }
    }

    /** Escribe un texto, o JsonNull si está vacío (para poder borrar el valor). */
    private fun JsonObjectBuilder.ponerTexto(clave: String, valor: String) {
        if (valor.isEmpty()) put(clave, JsonNull) else put(clave, valor)
    }

    /** Escribe un número, o JsonNull si no hay valor. */
    private fun JsonObjectBuilder.ponerNumero(clave: String, valor: Int?) {
        if (valor == null) put(clave, JsonNull) else put(clave, valor)
    }

    private companion object {
        /** Etiqueta para el log de errores de este paso. */
        const val TAG_LOG = "Step4Optional"

        /** Rangos aceptados de altura (cm) y peso (kg); ambos campos son opcionales. */
        const val ALTURA_MINIMA = 100
        const val ALTURA_MAXIMA = 250
        const val PESO_MINIMO = 30
        const val PESO_MAXIMO = 300
    }
}
