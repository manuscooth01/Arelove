package com.arelove.app.profile

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.ErrorSanitizer
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentStep2InterestsBinding
import com.arelove.app.ui.AreloveDialog
import com.google.android.material.chip.Chip
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Paso 2 del asistente de perfil: intereses.
 *
 * Carga el catálogo de la tabla `interests` (ORDER BY name ASC) y pinta un chip
 * por cada uno. La selección previa se lee de `user_interests` (user_id = auth.uid()).
 * Al guardar se borran las filas anteriores del usuario y se insertan las nuevas.
 */
class Step2InterestsFragment : Fragment() {

    private var _binding: FragmentStep2InterestsBinding? = null
    private val binding get() = _binding!!

    /** chipId → interestId, para traducir la selección de la vista al guardar. */
    private val interesesPorChip = mutableMapOf<Int, String>()

    /** Evita recargar la lista mientras ya se está cargando. */
    private var cargando = false

    /** Evita guardar dos veces si pulsan "Siguiente" repetidamente. */
    private var guardando = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep2InterestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configurarChipGroup()
        cargarIntereses()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Actualiza el contador y oculta el error en cuanto hay movimiento. */
    private fun configurarChipGroup() {
        binding.chipGroupInterests.setOnCheckedStateChangeListener { _, seleccionados ->
            val b = _binding ?: return@setOnCheckedStateChangeListener
            actualizarContador(seleccionados.size)

            if (seleccionados.size >= MINIMO_INTERESES) {
                b.tvInterestsError.visibility = View.GONE
            }
        }
    }

    /** Escribe el contador "X de 3 seleccionados" con sus plurales. */
    private fun actualizarContador(total: Int) {
        val b = _binding ?: return
        b.tvSelectionCount.text = if (total < MINIMO_INTERESES) {
            resources.getQuantityString(R.plurals.profile_step2_counter_min, total, total)
        } else {
            resources.getQuantityString(R.plurals.profile_step2_counter_ok, total, total)
        }
    }

    // ---------------------------------------------------------------------
    // Carga del catálogo
    // ---------------------------------------------------------------------

    /** SELECT * FROM interests ORDER BY name ASC, y preselección de los que ya tenía. */
    private fun cargarIntereses() {
        val b = _binding ?: return
        if (cargando) return
        cargando = true

        b.progressBarInterests.visibility = View.VISIBLE
        b.chipGroupInterests.removeAllViews()
        interesesPorChip.clear()
        actualizarContador(0)

        lifecycleScope.launch {
            try {
                val catalogo = SupabaseClient.client.postgrest["interests"].select {
                    order("name", Order.ASCENDING)
                }
                val intereses = catalogo.decodeList<JsonObject>()
                val mias = interesesGuardados()

                val despuesDeCargar = _binding ?: return@launch
                despuesDeCargar.progressBarInterests.visibility = View.GONE
                rellenarChips(despuesDeCargar, intereses, mias)
                actualizarContador(despuesDeCargar.chipGroupInterests.checkedChipIds.size)
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error cargando intereses", e)

                val contexto = context ?: return@launch
                val sinVista = _binding ?: return@launch
                sinVista.progressBarInterests.visibility = View.GONE

                AreloveDialog.show(
                    contexto,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_load_interests),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    ),
                    // Al aceptar volvemos a intentar la carga
                    onPositive = { cargarIntereses() }
                )
            } finally {
                cargando = false
            }
        }
    }

    /** Intereses ya marcados por el usuario: SELECT interest_id FROM user_interests. */
    private suspend fun interesesGuardados(): Set<String> {
        val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id ?: return emptySet()

        val resultado = SupabaseClient.client.postgrest["user_interests"].select {
            filter { eq("user_id", userId) }
        }

        return resultado.decodeList<JsonObject>()
            .mapNotNull { (it["interest_id"] as? JsonPrimitive)?.content }
            .toSet()
    }

    /** Crea un chip por interés y marca los que ya estaban guardados. */
    private fun rellenarChips(
        b: FragmentStep2InterestsBinding,
        intereses: List<JsonObject>,
        guardados: Set<String>
    ) {
        intereses.forEach { interes ->
            val interestId = (interes["id"] as? JsonPrimitive)?.content ?: return@forEach
            val nombre = (interes["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (nombre.isEmpty()) return@forEach

            val chip = Chip(b.root.context).apply {
                text = nombre
                isCheckable = true
                isCheckedIconVisible = true
                chipIconVisible = false
                id = View.generateViewId()
            }

            interesesPorChip[chip.id] = interestId
            b.chipGroupInterests.addView(chip)

            if (interestId in guardados) {
                b.chipGroupInterests.check(chip.id)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Validación y guardado
    // ---------------------------------------------------------------------

    /**
     * Valida que haya al menos 3 intereses y, si es así, reemplaza la selección
     * del usuario: DELETE de sus filas y INSERT de las nuevas.
     *
     * @param onSuccess se llama cuando el guardado termina bien (para avanzar de paso)
     * @param onError se llama con el mensaje de error a mostrar
     */
    fun validarYGuardar(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val b = _binding ?: return
        b.tvInterestsError.visibility = View.GONE

        val seleccionados = b.chipGroupInterests.checkedChipIds.mapNotNull { interesesPorChip[it] }

        if (seleccionados.size < MINIMO_INTERESES) {
            b.tvInterestsError.text = getString(R.string.profile_step2_error_min)
            b.tvInterestsError.visibility = View.VISIBLE
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
                // DELETE FROM user_interests WHERE user_id = ...
                SupabaseClient.client.postgrest["user_interests"].delete {
                    filter { eq("user_id", userId) }
                }

                // INSERT de la selección nueva (una sola petición)
                val filas = seleccionados.map { interesId ->
                    buildJsonObject {
                        put("user_id", userId)
                        put("interest_id", interesId)
                    }
                }
                if (filas.isNotEmpty()) {
                    SupabaseClient.client.postgrest["user_interests"].insert<JsonObject>(filas)
                }

                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error guardando intereses", e)
                onError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_save_interests),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                guardando = false
            }
        }
    }

    private companion object {
        /** Etiqueta para el log de errores de este paso. */
        const val TAG_LOG = "Step2Interests"

        /** Intereses mínimos para poder avanzar. */
        const val MINIMO_INTERESES = 3
    }
}
