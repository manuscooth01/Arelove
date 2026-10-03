package com.arelove.app.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.chip.Chip
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.arelove.app.BuildConfig
import com.arelove.app.ErrorSanitizer
import com.arelove.app.PhotoUploadHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentSwipeBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import coil.transform.RoundedCornersTransformation
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Fragment de descubrimiento (swipe).
 * Carga perfiles de Supabase y permite dar like/nope.
 * Implementa paginación infinita (load more) al llegar al final.
 */
class SwipeFragment : Fragment() {

    private var _binding: FragmentSwipeBinding? = null
    private val binding get() = _binding!!

    private val profiles = mutableListOf<SwipeProfile>()
    private var currentIndex = 0
    private var loading = false
    private var loadingNext = false
    private var currentUserId: String? = null

    // Paginación infinita
    private var offset = 0
    private var cargandoMas = false
    private var hayMasPerfiles = true

    companion object {
        private const val TAMANO_PAGINA = 2
    }

    private val photoLoadJobs = mutableMapOf<String, Job>()

    /** Coordenadas iniciales para detectar tap en photoPager (sin romper swipe). */
    private var tapXInicial = 0f
    private var tapYInicial = 0f

    /** Control anti-spam para rewind (1 uso cada 3s). */
    private var lastRewindTime = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSwipeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configurarBotones()
        configurarSwipeRefresh()
        aplicarBordeCardPerfil()
        configurarRewindButton()
        cargarPerfiles()

        // Touch listener en photoPager para detectar tap y abrir perfil (sin romper swipe)
        binding.photoPager.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    tapXInicial = event.x
                    tapYInicial = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.x - tapXInicial)
                    val dy = abs(event.y - tapYInicial)
                    if (dx < 15f && dy < 15f) {
                        val perfil = profiles.getOrNull(currentIndex)
                        if (perfil != null) {
                            val intent = Intent(requireContext(), ProfileDetailActivity::class.java).apply {
                                putExtra("user_id", perfil.userId)
                            }
                            startActivity(intent)
                        }
                    }
                    false
                }
                else -> false
            }
        }

        // Click en gradiente inferior también abre el perfil
        binding.llProfileInfo.setOnClickListener {
            val perfil = profiles.getOrNull(currentIndex) ?: return@setOnClickListener
            val intent = Intent(requireContext(), ProfileDetailActivity::class.java).apply {
                putExtra("user_id", perfil.userId)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        photoLoadJobs.values.forEach { it.cancel() }
        photoLoadJobs.clear()
        _binding = null
    }

    /**
     * Fija el drawable con borde crimson en la card principal.
     *
     * MaterialCardView descarta android:background durante la inflación
     * (su fondo lo pinta él con app:cardBackgroundColor), así que el
     * degradado/borde de bg_card_rim hay que aplicarlo tras inflar.
     */
    private fun aplicarBordeCardPerfil() {
        val b = _binding ?: return
        b.cardProfile.background = ContextCompat.getDrawable(
            requireContext(),
            R.drawable.bg_card_rim
        )
    }

    /** Pull-to-refresh: recarga la lista de perfiles deslizando hacia abajo. */
    private fun configurarSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (loading) {
                // Ya hay una carga en curso: solo cerramos el indicador
                binding.swipeRefresh.isRefreshing = false
            } else {
                currentIndex = 0
                offset = 0
                hayMasPerfiles = true
                profiles.clear()
                cargarPerfiles()
            }
        }
    }

    private fun configurarBotones() {
        binding.btnLike.setOnClickListener {
            if (AntiSpam.esDobleTap("btnLike")) return@setOnClickListener
            binding.btnLike.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                .withEndAction {
                    binding.btnLike.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            darLike()
        }

        binding.btnDislike.setOnClickListener {
            if (AntiSpam.esDobleTap("btnDislike")) return@setOnClickListener
            binding.btnDislike.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100)
                .withEndAction {
                    binding.btnDislike.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            pasarPerfil()
        }
    }

    /** Configura el botón REWIND (deshacer último swipe). */
    private fun configurarRewindButton() {
        binding.btnRewind.setOnClickListener {
            if (AntiSpam.esDobleTap("btnRewind")) return@setOnClickListener
            // Anti-spam adicional: 1 uso cada 3 segundos
            val ahora = System.currentTimeMillis()
            if (ahora - lastRewindTime < 3000) {
                Toast.makeText(requireContext(), "Espera un momento para deshacer", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastRewindTime = ahora
            deshacerUltimoSwipe()
        }
    }

    /** Carga la lista inicial de perfiles desde Supabase (página 0). */
    private fun cargarPerfiles() {
        val b = _binding ?: return
        if (loading) return
        loading = true
        b.progressBar.visibility = View.VISIBLE
        b.llEmpty.visibility = View.GONE
        b.cardProfile.visibility = View.GONE
        b.llActions.visibility = View.GONE
        b.progressCargandoMas.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id
                if (userId == null) {
                    mostrarError(getString(R.string.profile_error_no_session))
                    return@launch
                }
                currentUserId = userId

                // 1. Obtener IDs de likes dados por el usuario
                val likesFilas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("to_user")
                ) {
                    filter { eq("from_user", userId) }
                }.decodeList<JsonObject>()

                val likedUserIds = likesFilas.mapNotNull { (it["to_user"] as? JsonPrimitive)?.content }.toSet()

                // 2. Obtener IDs de likes recibidos por el usuario
                val likesRecibidosFilas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("from_user")
                ) {
                    filter { eq("to_user", userId) }
                }.decodeList<JsonObject>()

                val likesRecibidosUserIds = likesRecibidosFilas.mapNotNull { (it["from_user"] as? JsonPrimitive)?.content }.toSet()

                // 3. Obtener IDs de matches
                val matchesFilas1 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("user1", "user2")
                ) {
                    filter { eq("user1", userId) }
                }.decodeList<JsonObject>()

                val matchesFilas2 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("user1", "user2")
                ) {
                    filter { eq("user2", userId) }
                }.decodeList<JsonObject>()

                val matchesUserIds = (matchesFilas1 + matchesFilas2)
                    .mapNotNull { fila ->
                        val u1 = (fila["user1"] as? JsonPrimitive)?.content.orEmpty()
                        val u2 = (fila["user2"] as? JsonPrimitive)?.content.orEmpty()
                        if (u1 == userId) u2 else if (u2 == userId) u1 else null
                    }
                    .filter { it.isNotBlank() }
                    .toSet()

                // 4. Obtener IDs de swipes (likes + passes) ya realizados
                val swipesFilas = SupabaseClient.client.postgrest["swipes"].select(
                    columns = Columns.list("target_id")
                ) {
                    filter { eq("user_id", userId) }
                }.decodeList<JsonObject>()

                val swipedUserIds = swipesFilas.mapNotNull { (it["target_id"] as? JsonPrimitive)?.content }.toSet()

                // Combinar todos los IDs a excluir + el propio usuario
                val idsExcluidos = likedUserIds + likesRecibidosUserIds + matchesUserIds + swipedUserIds + userId

                // SELECT perfiles: profile_completed = true, excluyendo IDs, con paginación
                val filas = SupabaseClient.client.postgrest["profiles_publicos"].select(
                    columns = Columns.list("id", "nickname", "age", "location", "looking_for", "bio", "verified", "height", "zodiac", "occupation", "gender", "created_at")
                ) {
                    filter { neq("id", userId) }
                    filter { eq("profile_completed", true) }
                    order("verified", Order.DESCENDING)
                    order("created_at", Order.DESCENDING)
                    range(offset.toLong() .. (offset + TAMANO_PAGINA - 1).toLong())
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch
                sinVista.progressBar.visibility = View.GONE
                sinVista.progressCargandoMas.visibility = View.GONE

                if (filas.isEmpty()) {
                    hayMasPerfiles = false
                    if (profiles.isEmpty()) {
                        sinVista.llEmpty.visibility = View.VISIBLE
                        sinVista.llActions.visibility = View.GONE
                        b.btnRewind.visibility = View.GONE
                    } else {
                        hayMasPerfiles = false
                    }
                    return@launch
                }

                // Si la página viene con menos elementos que TAMANO_PAGINA, no hay más
                if (filas.size < TAMANO_PAGINA) {
                    hayMasPerfiles = false
                } else {
                    hayMasPerfiles = true
                }

                // Batch load interests for all profiles at once (evita N+1)
                val profileUserIds = filas.mapNotNull { textoDe(it, "id") }.filter { it !in idsExcluidos }
                val interestsMap = if (profileUserIds.isNotEmpty()) {
                    cargarInteresesBatch(profileUserIds)
                } else {
                    emptyMap<String, List<String>>()
                }

                // Si es la primera página (offset = 0), limpiar lista
                if (offset == 0) {
                    profiles.clear()
                }

                profiles.addAll(
                    filas.mapNotNull { parseProfile(it, interestsMap[textoDe(it, "id") ?: ""] ?: emptyList()) }
                        .filter { perfil -> perfil.userId !in idsExcluidos }
                )

                if (profiles.isEmpty()) {
                    sinVista.llEmpty.visibility = View.VISIBLE
                    sinVista.llActions.visibility = View.GONE
                    b.btnRewind.visibility = View.GONE
                } else {
                    // Solo resetear currentIndex si es la primera página
                    if (offset == 0) {
                        currentIndex = 0
                    }
                    sinVista.cardProfile.visibility = View.VISIBLE
                    sinVista.llActions.visibility = View.VISIBLE
                    sinVista.btnRewind.visibility = if (currentIndex > 0) View.VISIBLE else View.GONE
                    mostrarPerfilActual()
                    precargarSiguiente()
                }

                // Incrementar offset para la siguiente página
                if (hayMasPerfiles) {
                    offset += profiles.size
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error cargando perfiles", e)
                mostrarError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_generic),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                loading = false
                _binding?.swipeRefresh?.isRefreshing = false
                _binding?.progressCargandoMas?.visibility = View.GONE
            }
        }
    }

    /** Carga más perfiles (página siguiente) para scroll infinito. */
    private fun cargarMasPerfiles() {
        val b = _binding ?: return
        if (cargandoMas || !hayMasPerfiles) return
        if (loading) return // Evitar conflicto con carga inicial

        cargandoMas = true
        b.progressCargandoMas.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id ?: return@launch
                currentUserId = userId

                // Obtener IDs a excluir (mismo proceso que en cargarPerfiles)
                val likesFilas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("to_user")
                ) {
                    filter { eq("from_user", userId) }
                }.decodeList<JsonObject>()

                val likedUserIds = likesFilas.mapNotNull { (it["to_user"] as? JsonPrimitive)?.content }.toSet()

                val likesRecibidosFilas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("from_user")
                ) {
                    filter { eq("to_user", userId) }
                }.decodeList<JsonObject>()

                val likesRecibidosUserIds = likesRecibidosFilas.mapNotNull { (it["from_user"] as? JsonPrimitive)?.content }.toSet()

                val matchesFilas1 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("user1", "user2")
                ) {
                    filter { eq("user1", userId) }
                }.decodeList<JsonObject>()

                val matchesFilas2 = SupabaseClient.client.postgrest["matches"].select(
                    columns = Columns.list("user1", "user2")
                ) {
                    filter { eq("user2", userId) }
                }.decodeList<JsonObject>()

                val matchesUserIds = (matchesFilas1 + matchesFilas2)
                    .mapNotNull { fila ->
                        val u1 = (fila["user1"] as? JsonPrimitive)?.content.orEmpty()
                        val u2 = (fila["user2"] as? JsonPrimitive)?.content.orEmpty()
                        if (u1 == userId) u2 else if (u2 == userId) u1 else null
                    }
                    .filter { it.isNotBlank() }
                    .toSet()

                val swipesFilas = SupabaseClient.client.postgrest["swipes"].select(
                    columns = Columns.list("target_id")
                ) {
                    filter { eq("user_id", userId) }
                }.decodeList<JsonObject>()

                val swipedUserIds = swipesFilas.mapNotNull { (it["target_id"] as? JsonPrimitive)?.content }.toSet()

                val idsExcluidos = likedUserIds + likesRecibidosUserIds + matchesUserIds + swipedUserIds + (currentUserId ?: "")

                // SELECT siguiente página con range
                val filas = SupabaseClient.client.postgrest["profiles_publicos"].select(
                    columns = Columns.list("id", "nickname", "age", "location", "looking_for", "bio", "verified", "height", "zodiac", "occupation", "gender", "created_at")
                ) {
                    filter { neq("id", currentUserId!!) }
                    filter { eq("profile_completed", true) }
                    order("verified", Order.DESCENDING)
                    order("created_at", Order.DESCENDING)
                    range(offset.toLong() .. (offset + TAMANO_PAGINA - 1).toLong())
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch

                if (filas.isEmpty()) {
                    hayMasPerfiles = false
                    requireActivity().runOnUiThread { sinVista.progressCargandoMas.visibility = View.GONE }
                    return@launch
                }

                if (filas.size < TAMANO_PAGINA) {
                    hayMasPerfiles = false
                }

                // Batch load interests
                val profileUserIds = filas.mapNotNull { textoDe(it, "id") }.filter { it !in idsExcluidos }
                val interestsMap = if (profileUserIds.isNotEmpty()) {
                    cargarInteresesBatch(profileUserIds)
                } else {
                    emptyMap<String, List<String>>()
                }

                val nuevosPerfiles = filas.mapNotNull { parseProfile(it, interestsMap[textoDe(it, "id") ?: ""] ?: emptyList()) }
                    .filter { perfil -> perfil.userId !in idsExcluidos }

                if (nuevosPerfiles.isNotEmpty()) {
                    profiles.addAll(nuevosPerfiles)
                    offset += nuevosPerfiles.size
                }

                // Si no hay más perfiles, ocultar indicador
                if (!hayMasPerfiles) {
                    requireActivity().runOnUiThread { sinVista.progressCargandoMas.visibility = View.GONE }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error cargando más perfiles", e)
            } finally {
                cargandoMas = false
                _binding?.progressCargandoMas?.visibility = View.GONE
            }
        }
    }

    /** Carga intereses de múltiples usuarios en una sola query (batch). */
    private suspend fun cargarInteresesBatch(userIds: List<String>): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val filas = SupabaseClient.client.postgrest["user_interests"].select(
                    columns = Columns.list("user_id", "interests(name)")
                ) {
                    filter { isIn("user_id", userIds) }
                }.decodeList<JsonObject>()

                val map = mutableMapOf<String, MutableList<String>>()
                for (fila in filas) {
                    val uid = textoDe(fila, "user_id") ?: continue
                    val interestObj = fila["interests"] as? JsonObject ?: continue
                    val name = textoDe(interestObj, "name") ?: continue
                    map.getOrPut(uid) { mutableListOf() }.add(name)
                }
                map.mapValues { (_, v) -> v.toList() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SwipeFragment", "Error cargando intereses batch", e)
                emptyMap()
            }
        }
    }

    /** Parsea un JsonObject de profiles a SwipeProfile (sin fotos aún). */
    private fun parseProfile(fila: JsonObject, intereses: List<String>): SwipeProfile? {
        val userId = textoDe(fila, "id") ?: return null
        val nickname = textoDe(fila, "nickname") ?: return null
        val age = fila["age"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val location = textoDe(fila, "location")
        val bio = textoDe(fila, "bio")
        val verified = fila["verified"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
        val height = fila["height"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val zodiac = textoDe(fila, "zodiac")
        val occupation = textoDe(fila, "occupation")
        val lookingFor = textoDe(fila, "looking_for")
        val gender = textoDe(fila, "gender")

        return SwipeProfile(
            userId = userId,
            nickname = nickname,
            age = age,
            location = location,
            bio = bio,
            verified = verified,
            fotos = emptyList(),
            height = height,
            zodiac = zodiac,
            occupation = occupation,
            intereses = intereses,
            lookingFor = lookingFor,
            gender = gender
        )
    }

    /** Carga las fotos del perfil actual y lo muestra en la UI. */
    private fun mostrarPerfilActual() {
        val b = _binding ?: return
        if (currentIndex >= profiles.size) {
            b.llEmpty.visibility = View.VISIBLE
            b.cardProfile.visibility = View.GONE
            b.llActions.visibility = View.GONE
            b.cardNext.visibility = View.GONE
            b.btnRewind.visibility = View.GONE
            return
        }

        val perfil = profiles[currentIndex]
        b.tvName.text = buildNameString(perfil)
        b.tvLocation.text = perfil.location ?: ""
        b.tvBio.text = perfil.bio ?: ""
        b.ivVerified.visibility = if (perfil.verified) View.VISIBLE else View.GONE

        // "Busca pareja/amistades/ambos" - con colores dinámicos
        when (perfil.lookingFor) {
            "pareja" -> {
                b.tvLookingFor.visibility = View.VISIBLE
                b.tvLookingFor.text = getString(R.string.looking_pareja_short)
                b.tvLookingFor.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.looking_pareja_color)
            }
            "amistades" -> {
                b.tvLookingFor.visibility = View.VISIBLE
                b.tvLookingFor.text = getString(R.string.looking_amistades_short)
                b.tvLookingFor.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.looking_amistades_color)
            }
            "ambos" -> {
                b.tvLookingFor.visibility = View.VISIBLE
                b.tvLookingFor.text = getString(R.string.looking_ambos_short)
                b.tvLookingFor.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.looking_ambos_color)
            }
            else -> b.tvLookingFor.visibility = View.GONE
        }

        // Género - con ícono y colores dinámicos
        when (perfil.gender) {
            "hombre" -> {
                b.llGender.visibility = View.VISIBLE
                b.ivGenderIcon.setImageResource(R.drawable.ic_gender_male)
                b.ivGenderIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.gender_male_color))
                b.tvGender.text = getString(R.string.gender_hombre)
                b.tvGender.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gender_male_color))
            }
            "mujer" -> {
                b.llGender.visibility = View.VISIBLE
                b.ivGenderIcon.setImageResource(R.drawable.ic_gender_female)
                b.ivGenderIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.gender_female_color))
                b.tvGender.text = getString(R.string.gender_mujer)
                b.tvGender.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gender_female_color))
            }
            "otro" -> {
                b.llGender.visibility = View.VISIBLE
                b.ivGenderIcon.setImageResource(R.drawable.ic_gender_other)
                b.ivGenderIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.gender_other_color))
                b.tvGender.text = getString(R.string.gender_otro)
                b.tvGender.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gender_other_color))
            }
            else -> b.llGender.visibility = View.GONE
        }

        // Mostrar detalles opcionales (altura, zodiaco, ocupación)
        val optionalParts = mutableListOf<String>()
        perfil.height?.let { optionalParts.add("${it} cm") }
        perfil.zodiac?.let { optionalParts.add(it) }
        perfil.occupation?.let { optionalParts.add(it) }
        if (optionalParts.isNotEmpty()) {
            b.tvOptionalDetails.text = optionalParts.joinToString(" · ")
            b.tvOptionalDetails.visibility = View.VISIBLE
        } else {
            b.tvOptionalDetails.visibility = View.GONE
        }

        // Poblar chips de intereses
        poblarChipsIntereses(perfil.intereses)

        // Animación de entrada de la card (fade + scale)
        b.cardProfile.alpha = 0f
        b.cardProfile.scaleX = 0.95f
        b.cardProfile.scaleY = 0.95f
        b.cardProfile.visibility = View.VISIBLE
        b.cardProfile.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(250)
            .start()

        b.llActions.visibility = View.VISIBLE
        // Botón rewind visible si hay historial (currentIndex > 0)
        b.btnRewind.visibility = if (currentIndex > 0) View.VISIBLE else View.GONE

        // CardNext: mostrar si hay siguiente perfil
        val haySiguiente = currentIndex + 1 < profiles.size
        b.cardNext.visibility = if (haySiguiente) View.VISIBLE else View.GONE
        if (haySiguiente) {
            b.cardNext.alpha = 0.6f
            b.cardNext.scaleX = 0.95f
            b.cardNext.scaleY = 0.95f
            b.cardNext.translationY = 8f
        }

        // Disparar carga de más perfiles cuando quedan 3 perfiles
        if (currentIndex >= profiles.size - 3 && hayMasPerfiles && !cargandoMas) {
            cargarMasPerfiles()
        }

        // Configurar ViewPager2 con las fotos
        if (perfil.fotos.isEmpty()) {
            cargarFotosDePerfil(perfil)
        } else {
            setupPhotoPager(perfil.fotos)
        }
    }

    /** Pobla el ChipGroup con los intereses del perfil. */
    private fun poblarChipsIntereses(intereses: List<String>) {
        val b = _binding ?: return
        b.chipGroupInterests.removeAllViews()

        if (intereses.isEmpty()) {
            b.chipGroupInterests.visibility = View.GONE
            return
        }

        b.chipGroupInterests.visibility = View.VISIBLE
        val maxChips = 4
        val chipsAMostrar = intereses.take(maxChips)

        val marginEndPx = (8 * resources.displayMetrics.density).toInt()
        val backgroundColor = resources.getColor(R.color.surface_container_high, requireContext().theme)
        val strokeColor = resources.getColor(R.color.crimson_rim, requireContext().theme)
        for (interes in chipsAMostrar) {
            val chip = Chip(requireContext()).apply {
                text = interes
                isClickable = false
                isCheckable = false
                setTextAppearanceResource(R.style.Widget_Arelove_Chip_Small)
                chipBackgroundColor = ColorStateList.valueOf(backgroundColor)
                chipStrokeColor = ColorStateList.valueOf(strokeColor)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = marginEndPx
            }
            chip.layoutParams = lp
            b.chipGroupInterests.addView(chip)
        }
    }

    /** Construye el string "Nombre, edad" para el TextView. */
    private fun buildNameString(perfil: SwipeProfile): String {
        val nombre = perfil.nickname
        val edad = perfil.age?.let { ", $it" } ?: ""
        return "$nombre$edad"
    }

    /** Carga las fotos de un perfil desde la tabla media. */
    private fun cargarFotosDePerfil(perfil: SwipeProfile) {
        val b = _binding ?: return
        val userId = perfil.userId

        lifecycleScope.launch {
            try {
                // SELECT url FROM media WHERE user_id = ? AND type = 'profile' ORDER BY created_at ASC LIMIT 6
                val filas = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("url")
                ) {
                    filter { eq("user_id", userId) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                    limit(6)
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch
                val urls = filas.mapNotNull { textoDe(it, "url") }

                // Actualizar el perfil en la lista con las fotos
                if (currentIndex < profiles.size) {
                    val perfilActualizado = profiles[currentIndex].copy(fotos = urls)
                    profiles[currentIndex] = perfilActualizado
                    setupPhotoPager(urls)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("SwipeFragment", "Error cargando fotos de $userId", e)
                }
                val sinVista = _binding ?: return@launch
                setupPhotoPager(emptyList())
            }
        }
    }

    /** Configura el ViewPager2 con las URLs de fotos y barras tipo stories. */
    private fun setupPhotoPager(urls: List<String>) {
        val b = _binding ?: return
        val adapter = PhotoPagerAdapter(urls)
        b.photoPager.adapter = adapter

        // Configurar barras tipo stories
        actualizarBarrasStories(urls.size, 0)

        // Listener de cambio de página
        b.photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                actualizarBarrasStories(urls.size, position)
            }
        })
    }

    /** Crea/actualiza las barras tipo stories (reemplazan dots). */
    private fun actualizarBarrasStories(total: Int, actual: Int) {
        val b = _binding ?: return
        b.llStoryIndicators.removeAllViews()

        if (total <= 1) {
            b.llStoryIndicators.visibility = View.GONE
            return
        }
        b.llStoryIndicators.visibility = View.VISIBLE

        val heightPx = (3 * resources.displayMetrics.density).toInt()
        val marginPx = (4 * resources.displayMetrics.density).toInt()
        val radiusPx = (2 * resources.displayMetrics.density).toInt()

        repeat(total) { i ->
            val bar = View(requireContext())
            val lp = LinearLayout.LayoutParams(0, heightPx, 1f)
            lp.marginEnd = marginPx
            bar.layoutParams = lp
            bar.background = resources.getDrawable(
                if (i == actual) R.drawable.bg_story_bar_active
                else R.drawable.bg_story_bar_inactive,
                requireContext().theme
            )
            b.llStoryIndicators.addView(bar)
        }
    }

    /** Precarga las fotos del siguiente perfil. */
    private fun precargarSiguiente() {
        val siguiente = currentIndex + 1
        if (siguiente >= profiles.size || loadingNext) return

        val perfil = profiles[siguiente]
        if (perfil.fotos.isNotEmpty()) return // ya cargadas

        loadingNext = true
        lifecycleScope.launch {
            try {
                val filas = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("url")
                ) {
                    filter { eq("user_id", perfil.userId) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                    limit(6)
                }.decodeList<JsonObject>()

                val urls = filas.mapNotNull { textoDe(it, "url") }
                if (siguiente < profiles.size) {
                    profiles[siguiente] = profiles[siguiente].copy(fotos = urls)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error precargando fotos", e)
            } finally {
                loadingNext = false
            }
        }
    }

    /** Da like al perfil actual y registra swipe. */
    private fun darLike() {
        val b = _binding ?: return
        if (currentIndex >= profiles.size) return

        val perfil = profiles[currentIndex]
        val fromUserId = currentUserId ?: return

        b.btnLike.isEnabled = false
        b.btnDislike.isEnabled = false

        lifecycleScope.launch {
            try {
                // 1. INSERT en likes {from_user, to_user}
                SupabaseClient.client.postgrest["likes"].insert<JsonObject>(buildJsonObject {
                    put("from_user", fromUserId)
                    put("to_user", perfil.userId)
                })

                // 2. Registrar swipe en tabla swipes (fire-and-forget)
                registrarSwipe(fromUserId, perfil.userId, "like")

                // 3. Verificar si hay match (la otra persona ya dio like)
                val match = verificarMatch(fromUserId, perfil.userId)

                val sinVista = _binding ?: return@launch
                sinVista.btnLike.isEnabled = true
                sinVista.btnDislike.isEnabled = true

                if (match) {
                    AreloveDialog.show(
                        requireContext(),
                        AreloveDialog.Tipo.SUCCESS,
                        getString(R.string.match_title),
                        getString(R.string.match_subtitle),
                        getString(R.string.match_btn_chat),
                        onPositive = { /* por ahora solo cierra */ }
                    )
                }

                pasarPerfil()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _binding?.btnLike?.isEnabled = true
                _binding?.btnDislike?.isEnabled = true
                throw e
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error dando like", e)
                val sinVista = _binding ?: return@launch
                sinVista.btnLike.isEnabled = true
                sinVista.btnDislike.isEnabled = true
                if (e.message?.contains("duplicate") == true || e.message?.contains("unique constraint") == true) {
                    pasarPerfil()
                } else {
                    mostrarError(
                        getString(
                            R.string.profile_error_save_detail,
                            getString(R.string.error_generic),
                            ErrorSanitizer.limpiar(e.message ?: e.toString())
                        )
                    )
                }
            }
        }
    }

    /** Registra un swipe (like o pass) en la tabla swipes. Fire-and-forget. */
    private suspend fun registrarSwipe(userId: String, targetId: String, action: String) {
        try {
            SupabaseClient.client.postgrest["swipes"].insert<JsonObject>(buildJsonObject {
                put("user_id", userId)
                put("target_id", targetId)
                put("action", action)
            })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SwipeFragment", "Error registrando swipe $action", e)
        }
    }

    /** Pasa al siguiente perfil (registra swipe 'pass'). */
    private fun pasarPerfil() {
        val b = _binding ?: return
        if (currentIndex >= profiles.size) return

        val perfil = profiles[currentIndex]
        val fromUserId = currentUserId ?: return

        // Registrar swipe 'pass' (fire-and-forget)
        lifecycleScope.launch {
            registrarSwipe(fromUserId, perfil.userId, "pass")
        }

        currentIndex++

        if (currentIndex >= profiles.size) {
            b.llEmpty.visibility = View.VISIBLE
            b.cardProfile.visibility = View.GONE
            b.llActions.visibility = View.GONE
            b.cardNext.visibility = View.GONE
            b.btnRewind.visibility = View.GONE
        } else {
            // Animar cardNext subiendo a posición de cardProfile
            b.cardNext.animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(300)
                .withEndAction {
                    mostrarPerfilActual()
                    precargarSiguiente()
                }
                .start()
        }
    }

    /** Deshace el último swipe (REWIND). */
    private fun deshacerUltimoSwipe() {
        val b = _binding ?: return
        val userId = currentUserId ?: return

        lifecycleScope.launch {
            try {
                // 1. Obtener el último swipe del usuario
                val filas = SupabaseClient.client.postgrest["swipes"].select(
                    columns = Columns.list("id", "target_id", "action")
                ) {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                    limit(1)
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch

                if (filas.isEmpty()) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "No hay swipes para deshacer", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val fila = filas[0]
                val swipeId = textoDe(fila, "id") ?: return@launch
                val targetId = textoDe(fila, "target_id") ?: return@launch
                val action = textoDe(fila, "action") ?: return@launch

                // 2. Eliminar el swipe
                SupabaseClient.client.postgrest["swipes"].delete {
                    filter { eq("id", swipeId) }
                }

                // 3. Si era like, eliminar también de likes (y match si existe)
                if (action == "like") {
                    // Eliminar like mutuo si existe
                    SupabaseClient.client.postgrest["likes"].delete {
                        filter { eq("from_user", currentUserId!!) }
                        filter { eq("to_user", targetId) }
                    }
                    // Eliminar like recibido si existe (like mutuo)
                    SupabaseClient.client.postgrest["likes"].delete {
                        filter { eq("from_user", targetId) }
                        filter { eq("to_user", currentUserId!!) }
                    }
                    // Eliminar match si existe
                    val u1 = if (currentUserId!!.compareTo(targetId) < 0) currentUserId!! else targetId
                    val u2 = if (currentUserId!!.compareTo(targetId) < 0) targetId else currentUserId!!
                    SupabaseClient.client.postgrest["matches"].delete {
                        filter { eq("user1", u1); eq("user2", u2) }
                    }
                    // Eliminar chat si existe (sin mensajes)
                    SupabaseClient.client.postgrest["chats"].delete {
                        filter { eq("user1", u1); eq("user2", u2) }
                    }
                }

                // 4. Decrementar índice y volver a mostrar perfil anterior
                if (currentIndex > 0) {
                    currentIndex--
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "Swipe deshecho", Toast.LENGTH_SHORT).show()
                    }
                    mostrarPerfilActual()
                } else {
                    // Si estábamos en el primero, recargar
                    cargarPerfiles()
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error en rewind", e)
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Error al deshacer: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Verifica si la otra persona ya dio like (match). */
    private suspend fun verificarMatch(fromUserId: String, toUserId: String): Boolean {
        try {
            val filas = SupabaseClient.client.postgrest["likes"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("from_user", toUserId) }
                filter { eq("to_user", fromUserId) }
                filter { gte("expires_at", Instant.now().toString()) }
            }.decodeList<JsonObject>()

            if (filas.isNotEmpty()) {
                crearMatch(fromUserId, toUserId)
                actualizarLikeMutuo(fromUserId, toUserId)
                return true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SwipeFragment", "Error verificando match", e)
        }
        return false
    }

    /** Crea la fila en matches y en chats. */
    private suspend fun crearMatch(user1: String, user2: String) {
        try {
            val u1 = if (user1.compareTo(user2) < 0) user1 else user2
            val u2 = if (user1.compareTo(user2) < 0) user2 else user1
            SupabaseClient.client.postgrest["matches"].insert<JsonObject>(buildJsonObject {
                put("user1", u1)
                put("user2", u2)
            })

            val chatExiste1 = SupabaseClient.client.postgrest["chats"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("user1", u1); eq("user2", u2) }
                limit(1)
            }.decodeList<JsonObject>()

            val chatExiste2 = SupabaseClient.client.postgrest["chats"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("user1", u2); eq("user2", u1) }
                limit(1)
            }.decodeList<JsonObject>()

            val chatExiste = (chatExiste1 + chatExiste2).isNotEmpty()

            if (!chatExiste) {
                SupabaseClient.client.postgrest["chats"].insert<JsonObject>(buildJsonObject {
                    put("user1", u1)
                    put("user2", u2)
                })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SwipeFragment", "Error creando match o chat", e)
        }
    }

    /** Actualiza el like mutuo poniendo expires_at = NULL. */
    private suspend fun actualizarLikeMutuo(fromUserId: String, toUserId: String) {
        try {
            SupabaseClient.client.postgrest["likes"].update<JsonObject>(buildJsonObject {
                put("expires_at", JsonNull)
            }) {
                filter { eq("from_user", toUserId) }
                filter { eq("to_user", fromUserId) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SwipeFragment", "Error actualizando like mutuo", e)
        }
    }

    private fun mostrarError(mensaje: String) {
        val contexto = context ?: return
        _binding?.progressBar?.visibility = View.GONE
        _binding?.cardProfile?.visibility = View.GONE
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje,
            onPositive = { cargarPerfiles() }
        )
    }

    /** Extrae texto de un JsonObject de forma segura. */
    private fun textoDe(fila: JsonObject, clave: String): String? {
        val elemento = fila[clave] as? JsonPrimitive ?: return null
        if (elemento is JsonNull) return null
        return elemento.content.trim().ifEmpty { null }
    }

    /** Adaptador simple para ViewPager2 de fotos. */
    private inner class PhotoPagerAdapter(private val urls: List<String>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val imageView: androidx.appcompat.widget.AppCompatImageView = view.findViewById(R.id.ivPhotoPage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            if (urls.isEmpty()) {
                holder.imageView.setImageResource(R.color.photo_placeholder)
            } else {
                val url = urls[position % urls.size]
                holder.imageView.load(url) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(20f, 0f))
                    placeholder(R.color.photo_placeholder)
                    error(R.color.photo_placeholder)
                }
            }
        }

        override fun getItemCount(): Int = urls.size.coerceAtLeast(1)
    }
}