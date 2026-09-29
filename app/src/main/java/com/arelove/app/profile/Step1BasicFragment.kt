package com.arelove.app.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentStep1BasicBinding
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Paso 1 del asistente de perfil: apodo, edad, lugar y qué buscas.
 *
 * Guarda los datos en la tabla `profiles` de Supabase con un UPSERT sobre `auth.uid()`.
 * Los valores de "looking_for" que se guardan son: "pareja", "amistades" o "ambos".
 */
class Step1BasicFragment : Fragment() {

    private var _binding: FragmentStep1BasicBinding? = null
    private val binding get() = _binding!!

    /** Evita guardar dos veces si pulsan "Siguiente" repetidamente. */
    private var guardando = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep1BasicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configurarCiudades()
        prellenarNickname()
        configurarChips()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Sugerencias de ciudades más comunes de Venezuela. */
    private fun configurarCiudades() {
        val ciudades = resources.getStringArray(R.array.venezuela_cities)
        binding.etLocation.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ciudades)
        )
    }

    /** Pre-llena el apodo con el user_metadata.nickname del usuario. */
    private fun prellenarNickname() {
        val metadata = SupabaseClient.client.auth.currentUserOrNull()?.userMetadata ?: return
        val nickname = (metadata["nickname"] as? JsonPrimitive)?.content?.trim()

        if (!nickname.isNullOrEmpty() && binding.etNickname.text.isNullOrBlank()) {
            binding.etNickname.setText(nickname)
        }
    }

    /** Quita el error de "Busco" en cuanto eligen una opción. */
    private fun configurarChips() {
        binding.chipGroupLooking.setOnCheckedStateChangeListener { _, idsSeleccionados ->
            if (idsSeleccionados.isNotEmpty()) {
                binding.tvLookingError.visibility = View.GONE
            }
        }
    }

    /** Opción seleccionada en los chips, o null si no hay ninguna. */
    private fun buscandoSeleccionado(): String? = when (binding.chipGroupLooking.checkedChipId) {
        R.id.chipPareja -> "pareja"
        R.id.chipAmistades -> "amistades"
        R.id.chipAmbos -> "ambos"
        else -> null
    }

    /**
     * Valida los campos del paso 1 y, si todo está correcto, hace un UPSERT
     * en la tabla `profiles` con id = auth.uid().
     *
     * @param onSuccess se llama cuando el guardado termina bien (para avanzar de paso)
     * @param onError se llama con el mensaje de error a mostrar
     */
    fun validarYGuardar(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val b = _binding ?: return

        // Limpiamos los errores de la validación anterior
        b.tilNickname.error = null
        b.tilAge.error = null
        b.tilLocation.error = null
        b.tvLookingError.visibility = View.GONE

        val nickname = b.etNickname.text?.toString()?.trim().orEmpty()
        val edadTexto = b.etAge.text?.toString()?.trim().orEmpty()
        val location = b.etLocation.text?.toString()?.trim().orEmpty()
        val buscando = buscandoSeleccionado()

        if (nickname.isEmpty()) {
            b.tilNickname.error = getString(R.string.required_field)
            return
        }

        if (edadTexto.isEmpty()) {
            b.tilAge.error = getString(R.string.required_field)
            return
        }

        val edad = edadTexto.toIntOrNull()
        if (edad == null || edad < EDAD_MINIMA || edad > EDAD_MAXIMA) {
            b.tilAge.error = getString(R.string.profile_error_age_range)
            return
        }

        if (location.isEmpty()) {
            b.tilLocation.error = getString(R.string.required_field)
            return
        }

        if (buscando == null) {
            b.tvLookingError.text = getString(R.string.profile_error_looking_for)
            b.tvLookingError.visibility = View.VISIBLE
            return
        }

        val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
        if (userId == null) {
            onError(getString(R.string.profile_error_no_session))
            return
        }

        if (guardando) return
        guardando = true

        lifecycleScope.launch {
            try {
                val fila = buildJsonObject {
                    put("id", userId)
                    put("nickname", nickname)
                    put("age", edad)
                    put("location", location)
                    put("looking_for", buscando)
                }
                // UPSERT: crea la fila si no existe y la actualiza si ya existe
                SupabaseClient.client.postgrest["profiles"].upsert(fila)
                onSuccess()
            } catch (e: Exception) {
                onError(getString(R.string.profile_error_save))
            } finally {
                // Siempre se libera el bloqueo para poder reintentar
                guardando = false
            }
        }
    }

    private companion object {
        const val EDAD_MINIMA = 18
        const val EDAD_MAXIMA = 100
    }
}
