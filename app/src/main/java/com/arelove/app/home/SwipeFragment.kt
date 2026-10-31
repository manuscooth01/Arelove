package com.arelove.app.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.chip.Chip
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.arelove.app.BuildConfig
import com.arelove.app.ErrorSanitizer
import com.arelove.app.LogHelper
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
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

    /** Balance actual de diamantes del usuario (cache local). */
    private var diamantesActuales = 0

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
        cargarBalanceDiamantes()

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

    /** Configura el botón REWIND (deshacer último swipe) — 5 diamantes. */
    private fun configurarRewindButton() {
        binding.btnRewind.setOnClickListener { view ->
            if (AntiSpam.esDobleTap("btnRewind")) return@setOnClickListener

            // Animación scale + haptic
            view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

            // Anti-spam: 1 uso cada 3 segundos — Snackbar no bloqueante
            val ahora = System.currentTimeMillis()
            if (ahora - lastRewindTime < 3000) {
                val b = _binding ?: return@setOnClickListener
                Snackbar.make(
                    b.root,
                    getString(R.string.rewind_wait_msg),
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Validar que haya algo que deshacer
            if (currentIndex <= 0) {
                AreloveDialog.show(
                    requireContext(),
                    AreloveDialog.Tipo.INFO,
                    getString(R.string.rewind_nothing_title),
                    getString(R.string.rewind_nothing_msg),
                    getString(R.string.ok)
                )
                return@setOnClickListener
            }

            // Verificar balance de diamantes
            if (diamantesActuales < 5) {
                AreloveDialog.show(
                    requireContext(),
                    AreloveDialog.Tipo.INFO,
                    getString(R.string.rewind_no_diamonds_title),
                    getString(R.string.rewind_no_diamonds_msg, diamantesActuales),
                    getString(R.string.ok)
                )
                return@setOnClickListener
            }

            // Confirmar gasto (diálogo INFO con botón "Gastar 5 diamantes")
            AreloveDialog.show(
                requireContext(),
                AreloveDialog.Tipo.INFO,
                getString(R.string.rewind_confirm_title),
                getString(R.string.rewind_confirm_msg, diamantesActuales),
                getString(R.string.rewind_btn_spend),
                { gastarDiamantesYRewind() }
            )
        }
    }

    /** Carga el balance de diamantes del usuario actual vía RPC. */
    private fun cargarBalanceDiamantes() {
        val userId = currentUserId ?: return
        lifecycleScope.launch {
            val b = _binding ?: return@launch
            SupabaseClient.rpc(
                functionName = "get_diamonds_balance",
                params = buildJsonObject { put("p_user_id", userId) },
                onResult = { result ->
                    diamantesActuales = result?.get("balance")?.let { (it as JsonPrimitive).int } ?: 0
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        actualizarUIBalanceDiamantes()
                    }
                },
                onError = { e ->
                    LogHelper.w("SwipeFragment", "Error cargando balance diamantes")
                }
            )
        }
    }

    /** Actualiza el TextView del balance y el estado visual del botón rewind. */
    private fun actualizarUIBalanceDiamantes() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val b = _binding ?: return@launch
            b.tvDiamondBalance.text = diamantesActuales.toString()
            val puedeRewind = diamantesActuales >= 5 && currentIndex > 0
            b.btnRewind.isEnabled = puedeRewind
            b.btnRewind.alpha = if (puedeRewind) 1f else 0.5f
        }
    }

    /** Ejecuta el gasto de 5 diamantes y si tiene éxito hace el rewind. */
    private fun gastarDiamantesYRewind() {
        val userId = currentUserId ?: return
        lifecycleScope.launch {
            val b = _binding ?: return@launch
            SupabaseClient.rpc(
                functionName = "spend_diamonds",
                params = buildJsonObject {
                    put("p_user_id", userId)
                    put("p_amount", 5)
                    put("p_reason", "rewind")
                },
                onResult = { result ->
                    val ok = result?.get("success")?.let { (it as JsonPrimitive).boolean } ?: false

                    if (ok) {
                        diamantesActuales -= 5
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            actualizarUIBalanceDiamantes()
                        }
                        deshacerUltimoSwipe()

                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            val b2 = _binding ?: return@launch
                            Snackbar.make(
                                b2.root,
                                getString(R.string.rewind_spent_snackbar, 5),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            val b2 = _binding ?: return@launch
                            AreloveDialog.show(
                                requireContext(),
                                AreloveDialog.Tipo.ERROR,
                                getString(R.string.rewind_error_title),
                                getString(R.string.rewind_error_msg),
                                getString(R.string.ok)
                            )
                        }
                    }
                },
                onError = { e ->
                    LogHelper.e("SwipeFragment", "Error gastando diamantes rewind")
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        val b2 = _binding ?: return@launch
                        AreloveDialog.show(
                            requireContext(),
                            AreloveDialog.Tipo.ERROR,
                            getString(R.string.rewind_error_title),
                            getString(R.string.rewind_error_msg),
                            getString(R.string.ok)
                        )
                    }
                }
            )
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
                LogHelper.e("SwipeFragment", "Error cargando perfiles")
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
                LogHelper.e("SwipeFragment", "Error cargando más perfiles")
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
                LogHelper.w("SwipeFragment", "Error cargando intereses batch")
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
            else -> b.llGender.visibility = View.GONE
        }
    }

    /** Construye el string nombre + edad para mostrar. */
    private fun buildNameString(perfil: SwipeProfile): String {
        return buildString {
            append(perfil.nickname)
            if (perfil.age != null) {
                append(", ${perfil.age}")
            }
        }
    }

    /** Deshace el último swipe (rewind). */
    private fun deshacerUltimoSwipe() {
        if (currentIndex > 0) {
            currentIndex--
            mostrarPerfilActual()
        }
    }

    /** Pre-carga la siguiente foto en segundo plano. */
    private fun precargarSiguiente() {
        if (currentIndex + 1 < profiles.size) {
            // TODO: pre-cargar imagen del siguiente perfil
        }
    }

    /** Extrae un texto de un JsonObject de forma segura. */
    private fun textoDe(obj: JsonObject, clave: String): String? {
        return (obj[clave] as? JsonPrimitive)?.content?.orNull()
    }

    /** Método stub para dar like (implementación pendiente). */
    private fun darLike() {
        // TODO: implementar like con Supabase
    }

    /** Método stub para pasar perfil (implementación pendiente). */
    private fun pasarPerfil() {
        // TODO: implementar pasar perfil con Supabase
    }

    /** Método stub para mostrar error (implementación pendiente). */
    private fun mostrarError(mensaje: String) {
        val b = _binding ?: return
        AreloveDialog.show(
            requireContext(),
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje
        )
    }
}