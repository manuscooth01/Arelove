package com.arelove.app.home

import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
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
 */
class SwipeFragment : Fragment() {

    private var _binding: FragmentSwipeBinding? = null
    private val binding get() = _binding!!

    private val profiles = mutableListOf<SwipeProfile>()
    private var currentIndex = 0
    private var loading = false
    private var loadingNext = false
    private var currentUserId: String? = null

    private val photoLoadJobs = mutableMapOf<String, Job>()

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
        cargarPerfiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        photoLoadJobs.values.forEach { it.cancel() }
        photoLoadJobs.clear()
        _binding = null
    }

    private fun configurarBotones() {
        binding.btnLike.setOnClickListener {
            if (AntiSpam.esDobleTap("btnLike")) return@setOnClickListener
            // Pulse animation on like button
            binding.btnLike.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                .withEndAction {
                    binding.btnLike.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            darLike()
        }

        binding.btnDislike.setOnClickListener {
            if (AntiSpam.esDobleTap("btnDislike")) return@setOnClickListener
            // Pulse animation on dislike button
            binding.btnDislike.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100)
                .withEndAction {
                    binding.btnDislike.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            pasarPerfil()
        }
    }

    private fun configurarClickCard() {
        binding.cardProfile.setOnClickListener {
            if (AntiSpam.esDobleTap("cardProfile")) return@setOnClickListener
            val perfil = profiles.getOrNull(currentIndex) ?: return@setOnClickListener
            val intent = Intent(requireContext(), ProfileDetailActivity::class.java).apply {
                putExtra("user_id", perfil.userId)
            }
            startActivity(intent)
        }
    }

    /** Carga la lista inicial de perfiles desde Supabase. */
    private fun cargarPerfiles() {
        val b = _binding ?: return
        if (loading) return
        loading = true
        b.progressBar.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE
        b.cardProfile.visibility = View.GONE
        b.llActions.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id
                if (userId == null) {
                    mostrarError(getString(R.string.profile_error_no_session))
                    return@launch
                }
                currentUserId = userId

                // SELECT to_user FROM likes WHERE from_user = currentUserId
                val likesFilas = SupabaseClient.client.postgrest["likes"].select(
                    columns = Columns.list("to_user")
                ) {
                    filter { eq("from_user", userId) }
                }.decodeList<JsonObject>()

                val likedUserIds = likesFilas.mapNotNull { (it["to_user"] as? JsonPrimitive)?.content }.toSet()

                // SELECT id, nickname, age, location, looking_for, bio, verified, height, zodiac, occupation
                // FROM profiles WHERE id != currentUserId AND profile_completed = true
                // ORDER BY verified DESC, created_at DESC LIMIT 30
                val filas = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list("id", "nickname", "age", "location", "looking_for", "bio", "verified", "height", "zodiac", "occupation", "created_at")
                ) {
                    filter { neq("id", userId) }
                    filter { eq("profile_completed", true) }
                    order("verified", Order.DESCENDING)
                    order("created_at", Order.DESCENDING)
                    limit(30)
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch
                sinVista.progressBar.visibility = View.GONE

                profiles.clear()
                profiles.addAll(
                    filas.mapNotNull { parseProfile(it) }
                        .filter { perfil -> perfil.userId !in likedUserIds }
                )

                if (profiles.isEmpty()) {
                    sinVista.tvEmpty.visibility = View.VISIBLE
                    sinVista.llActions.visibility = View.GONE
                } else {
                    currentIndex = 0
                    sinVista.cardProfile.visibility = View.VISIBLE
                    sinVista.llActions.visibility = View.VISIBLE
                    configurarClickCard()
                    mostrarPerfilActual()
                    precargarSiguiente()
                }
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
            }
        }
    }

    /** Parsea un JsonObject de profiles a SwipeProfile (sin fotos aún). */
    private fun parseProfile(fila: JsonObject): SwipeProfile? {
        val userId = textoDe(fila, "id") ?: return null
        val nickname = textoDe(fila, "nickname") ?: return null
        val age = fila["age"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val location = textoDe(fila, "location")
        val bio = textoDe(fila, "bio")
        val verified = fila["verified"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
        val height = fila["height"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val zodiac = textoDe(fila, "zodiac")
        val occupation = textoDe(fila, "occupation")

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
            occupation = occupation
        )
    }

    /** Carga las fotos del perfil actual y lo muestra en la UI. */
    private fun mostrarPerfilActual() {
        val b = _binding ?: return
        if (currentIndex >= profiles.size) {
            b.tvEmpty.visibility = View.VISIBLE
            b.cardProfile.visibility = View.GONE
            b.llActions.visibility = View.GONE
            return
        }

        val perfil = profiles[currentIndex]
        b.tvName.text = buildNameString(perfil)
        b.tvLocation.text = perfil.location ?: ""
        b.tvBio.text = perfil.bio ?: ""
        b.ivVerified.visibility = if (perfil.verified) View.VISIBLE else View.GONE

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

        // Configurar ViewPager2 con las fotos
        if (perfil.fotos.isEmpty()) {
            cargarFotosDePerfil(perfil)
        } else {
            setupPhotoPager(perfil.fotos)
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
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error cargando fotos de $userId", e)
                val sinVista = _binding ?: return@launch
                setupPhotoPager(emptyList())
            }
        }
    }

    /** Configura el ViewPager2 con las URLs de fotos. */
    private fun setupPhotoPager(urls: List<String>) {
        val b = _binding ?: return
        val adapter = PhotoPagerAdapter(urls)
        b.photoPager.adapter = adapter

        // Configurar indicadores lineales
        actualizarIndicadores(urls.size, 0)

        // Listener de cambio de página
        b.photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                actualizarIndicadores(urls.size, position)
            }
        })
    }

    /** Crea/actualiza los dots del indicador. */
    private fun actualizarIndicadores(total: Int, actual: Int) {
        val b = _binding ?: return
        b.llIndicators.removeAllViews()

        if (total <= 1) {
            b.llIndicators.visibility = View.GONE
            return
        }
        b.llIndicators.visibility = View.VISIBLE

        val tamanoPx = (8 * resources.displayMetrics.density).toInt()
        val margenPx = (4 * resources.displayMetrics.density).toInt()

        repeat(total) { i ->
            val dot = ImageView(requireContext())
            val lp = LinearLayout.LayoutParams(tamanoPx, tamanoPx)
            lp.marginStart = margenPx
            lp.marginEnd = margenPx
            dot.layoutParams = lp
            dot.setImageResource(
                if (i == actual) R.drawable.indicator_dot_active
                else R.drawable.indicator_dot_inactive
            )
            b.llIndicators.addView(dot)
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
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error precargando fotos", e)
            } finally {
                loadingNext = false
            }
        }
    }

    /** Da like al perfil actual. */
    private fun darLike() {
        val b = _binding ?: return
        if (currentIndex >= profiles.size) return

        val perfil = profiles[currentIndex]
        val fromUserId = currentUserId ?: return

        b.btnLike.isEnabled = false
        b.btnDislike.isEnabled = false

        lifecycleScope.launch {
            try {
                // INSERT en likes {from_user, to_user}
                SupabaseClient.client.postgrest["likes"].insert<JsonObject>(buildJsonObject {
                    put("from_user", fromUserId)
                    put("to_user", perfil.userId)
                })

                // Verificar si hay match (la otra persona ya dio like)
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
            } catch (e: Exception) {
                Log.e("SwipeFragment", "Error dando like", e)
                val sinVista = _binding ?: return@launch
                sinVista.btnLike.isEnabled = true
                sinVista.btnDislike.isEnabled = true
                // Si es error de duplicado, no mostrar error, solo pasar al siguiente
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

    /** Verifica si la otra persona ya dio like (match). */
    private suspend fun verificarMatch(fromUserId: String, toUserId: String): Boolean {
        try {
            // SELECT * FROM likes WHERE from_user = toUserId AND to_user = fromUserId AND expires_at > now()
            val filas = SupabaseClient.client.postgrest["likes"].select(
                columns = Columns.list("id")
            ) {
                filter { eq("from_user", toUserId) }
                filter { eq("to_user", fromUserId) }
                filter { gte("expires_at", Instant.now().toString()) }
            }.decodeList<JsonObject>()

            if (filas.isNotEmpty()) {
                // Crear match
                crearMatch(fromUserId, toUserId)
                // Actualizar/bloquear el like original
                actualizarLikeMutuo(fromUserId, toUserId)
                return true
            }
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

            // Crear chat asociado al match (si no existe)
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
        } catch (e: Exception) {
            Log.e("SwipeFragment", "Error actualizando like mutuo", e)
        }
    }

    /** Pasa al siguiente perfil. */
    private fun pasarPerfil() {
        val b = _binding ?: return
        currentIndex++

        if (currentIndex >= profiles.size) {
            b.tvEmpty.visibility = View.VISIBLE
            b.cardProfile.visibility = View.GONE
            b.llActions.visibility = View.GONE
        } else {
            mostrarPerfilActual()
            precargarSiguiente()
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