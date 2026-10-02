package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.LegalActivity
import com.arelove.app.MainActivity
import com.arelove.app.R
import com.arelove.app.SettingsActivity
import com.arelove.app.SupabaseClient
import com.arelove.app.admin.AdminActivity
import com.arelove.app.admin.AdminChecker
import com.arelove.app.databinding.FragmentProfileBinding
import com.arelove.app.notifications.OneSignalManager
import com.arelove.app.profile.EditProfileActivity
import com.arelove.app.profile.MyPhotosActivity
import com.arelove.app.profile.MyVerificationActivity
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fragment de Perfil - muestra avatar circular con inicial, nombre, email,
 * estado de verificación, opciones y botón logout.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    /** true si el equipo de Arelove ya verificó la identidad del usuario. */
    private var perfilVerificado = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configurarLogout()
        configurarCards()
        aplicarFondoDegradado()
        cargarEsAdmin()
    }

    /**
     * Aplica el degradado de fondo a las tarjetas del perfil.
     *
     * No puede ir en el XML: MaterialCardView descarta android:background
     * durante la inflación (su fondo lo pinta él con app:cardBackgroundColor,
     * que solo admite un color sólido). La única vía es fijarlo tras inflar.
     */
    private fun aplicarFondoDegradado() {
        val v = _binding ?: return
        listOf(
            v.cardCompletion, v.cardEditProfile, v.cardMyPhotos,
            v.cardVerification, v.cardTerms, v.cardPrivacy,
            v.cardCommunity, v.cardFaq, v.cardInvite, v.cardSettings,
            v.cardAdmin
        ).forEach { it.setBackgroundResource(R.drawable.bg_profile_card) }
    }

    /**
     * Muestra la card "Panel de administración" solo si profiles.is_admin
     * es true para el usuario actual (AdminChecker, con caché de 5 min).
     * Por defecto la card está oculta en el XML, así que un usuario
     * normal nunca la llega a ver.
     */
    private fun cargarEsAdmin() {
        lifecycleScope.launch {
            val esAdmin = AdminChecker.esAdmin()
            // Doble comprobación: sin sesión no se enseña la card, aunque
            // la caché dijera true. Se consulta la sesión DESPUÉS de
            // esAdmin(), que ya ha esperado a awaitInitialization().
            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
            _binding?.cardAdmin?.visibility =
                if (esAdmin && userId != null) View.VISIBLE else View.GONE
        }
    }

    /** Recarga los datos cada vez que el usuario vuelve a la pestaña (p. ej. tras editar). */
    override fun onResume() {
        super.onResume()
        if (_binding != null) cargarPerfil()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun cargarPerfil() {
        lifecycleScope.launch {
            var email = ""
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id
                email = session?.user?.email.orEmpty()
                if (userId == null) {
                    _binding?.tvEmail?.text = email
                    return@launch
                }

                // Cargar datos del perfil desde profiles (incluye los campos
                // que cuenta el banner de completitud)
                val filas = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list(
                        "nickname", "gender", "verified", "verification_photo_url",
                        "age", "location", "looking_for", "bio", "height",
                        "zodiac", "occupation", "favorite_music"
                    )
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeList<JsonObject>()

                val sinVista = _binding ?: return@launch
                sinVista.tvEmail.text = email
                if (filas.isNotEmpty()) {
                    val nickname = filas[0]["nickname"] as? JsonPrimitive
                    val nicknameStr = nickname?.content.orEmpty()
                    sinVista.tvName.text = nicknameStr
                    // Mostrar inicial en el avatar (fallback)
                    if (nicknameStr.isNotEmpty()) {
                        sinVista.tvAvatarInitial.text = nicknameStr.first().uppercase()
                        sinVista.tvAvatarInitial.visibility = View.VISIBLE
                    }

                    // Género
                    val gender = (filas[0]["gender"] as? JsonPrimitive)?.content.orEmpty()
                    val genderRes = when (gender) {
                        "hombre" -> R.string.gender_hombre
                        "mujer" -> R.string.gender_mujer
                        "otro" -> R.string.gender_otro
                        else -> null
                    }
                    val genderIcon = when (gender) {
                        "hombre" -> R.drawable.ic_gender_male
                        "mujer" -> R.drawable.ic_gender_female
                        "otro" -> R.drawable.ic_gender_other
                        else -> 0
                    }
                    val genderColor = when (gender) {
                        "hombre" -> R.color.gender_male_color
                        "mujer" -> R.color.gender_female_color
                        "otro" -> R.color.gender_other_color
                        else -> 0
                    }
                    if (genderRes != null && genderIcon != 0 && genderColor != 0) {
                        sinVista.tvGender.text = getString(genderRes)
                        sinVista.ivGenderIcon.setImageResource(genderIcon)
                        sinVista.ivGenderIcon.setColorFilter(ContextCompat.getColor(requireContext(), genderColor))
                        sinVista.tvGender.setTextColor(ContextCompat.getColor(requireContext(), genderColor))
                        sinVista.llGender.visibility = View.VISIBLE
                    } else {
                        sinVista.llGender.visibility = View.GONE
                    }

                    // Estado de verificación (badges del header + subtítulo de la card)
                    val verificado = filas[0]["verified"]
                        ?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
                    val selfieUrl = filas[0]["verification_photo_url"]
                        ?.let { (it as? JsonPrimitive)?.content }.orEmpty()
                    pintarVerificacion(verificado, selfieUrl)

                    // Fila de chips visible si hay género o verificación
                    sinVista.llHeaderChips.visibility =
                        if (sinVista.llGender.visibility == View.VISIBLE ||
                            sinVista.llVerifiedChip.visibility == View.VISIBLE
                        ) View.VISIBLE else View.GONE
                }

                // Fotos de perfil desde media (también alimenta el contador
                // del banner de completitud; el tope de 50 solo acota la query)
                val filasMedia = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("url")
                ) {
                    filter { eq("user_id", userId) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                    limit(50)
                }.decodeList<JsonObject>()

                if (filasMedia.isNotEmpty()) {
                    val fotoUrl = filasMedia[0]["url"] as? JsonPrimitive
                    val urlStr = fotoUrl?.content.orEmpty()
                    if (urlStr.isNotEmpty()) {
                        val sinVista2 = _binding ?: return@launch
                        sinVista2.ivAvatar.load(urlStr) {
                            crossfade(true)
                        }
                        sinVista2.tvAvatarInitial.visibility = View.GONE
                    }
                }

                // SELECT count(*) FROM user_interests WHERE user_id = userId
                // (countOrNull() devuelve Long: lo pasamos a Int para la cuenta)
                val intereses = SupabaseClient.client.postgrest["user_interests"]
                    .select(head = true) {
                        filter { eq("user_id", userId) }
                        count(Count.EXACT)
                    }
                    .countOrNull()?.toInt() ?: 0

                // Banner de completitud con los 13 indicadores del perfil
                if (filas.isNotEmpty()) {
                    pintarCompletitud(filas[0], intereses, filasMedia.size)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                _binding?.tvEmail?.text = email
            }
        }
    }

    private fun configurarLogout() {
        binding.btnLogout.setOnClickListener {
            if (AntiSpam.esDobleTap("btnLogout")) return@setOnClickListener
            cerrarSesion()
        }
    }

    private fun configurarCards() {
        // Tu cuenta: "Editar perfil" abre la edición directa y "Mis fotos"
        // la pantalla de fotos (ambas fuera del asistente)
        binding.cardEditProfile.setOnClickListener {
            if (AntiSpam.esDobleTap("cardEditProfile")) return@setOnClickListener
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }
        binding.cardMyPhotos.setOnClickListener {
            if (AntiSpam.esDobleTap("cardMyPhotos")) return@setOnClickListener
            startActivity(Intent(requireContext(), MyPhotosActivity::class.java))
        }
        // Verificación: confirmación si ya está verificado, si no abre la
        // pantalla de verificación suelta (sin el asistente de 5 pasos)
        binding.cardVerification.setOnClickListener {
            if (AntiSpam.esDobleTap("cardVerification")) return@setOnClickListener
            alPulsarVerificacion()
        }
        // Seguridad y privacidad: "Ajustes" se movió aquí desde Descubre más
        binding.cardSettings.setOnClickListener {
            if (AntiSpam.esDobleTap("cardSettings")) return@setOnClickListener
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        // Ayuda y legal
        binding.cardTerms.setOnClickListener {
            if (AntiSpam.esDobleTap("cardTerms")) return@setOnClickListener
            abrirLegal("terminos")
        }
        binding.cardPrivacy.setOnClickListener {
            if (AntiSpam.esDobleTap("cardPrivacy")) return@setOnClickListener
            abrirLegal("privacidad")
        }
        binding.cardCommunity.setOnClickListener {
            if (AntiSpam.esDobleTap("cardCommunity")) return@setOnClickListener
            abrirLegal("comunidad")
        }
        binding.cardFaq.setOnClickListener {
            if (AntiSpam.esDobleTap("cardFaq")) return@setOnClickListener
            abrirLegal("faq")
        }

        // Descubre más
        binding.cardInvite.setOnClickListener {
            if (AntiSpam.esDobleTap("cardInvite")) return@setOnClickListener
            compartirApp()
        }
        // Solo admins (visibilidad decidida en cargarEsAdmin)
        binding.cardAdmin.setOnClickListener {
            if (AntiSpam.esDobleTap("cardAdmin")) return@setOnClickListener
            startActivity(Intent(requireContext(), AdminActivity::class.java))
        }
    }

    /** Pinta badges de verificación del header y el estado de la card. */
    private fun pintarVerificacion(verificado: Boolean, selfieUrl: String) {
        perfilVerificado = verificado
        val v = _binding ?: return

        if (verificado) {
            v.ivVerifiedName.visibility = View.VISIBLE
            v.llVerifiedChip.visibility = View.VISIBLE
            v.tvVerificationStatus.visibility = View.VISIBLE
            v.tvVerificationStatus.text = getString(R.string.verification_status_verified)
            v.tvVerificationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.online_green)
            )
        } else {
            v.ivVerifiedName.visibility = View.GONE
            v.llVerifiedChip.visibility = View.GONE
            v.tvVerificationStatus.visibility = View.VISIBLE
            if (selfieUrl.isNotEmpty()) {
                v.tvVerificationStatus.text = getString(R.string.verification_status_pending)
            } else {
                v.tvVerificationStatus.text = getString(R.string.verification_status_empty)
            }
            v.tvVerificationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.profile_on_dark_muted)
            )
        }
    }

    /**
     * Pinta el banner de completitud: cuenta los 13 indicadores del perfil
     * (10 datos sueltos + intereses + fotos + selfie verificada) y
     * muestra el porcentaje cumplido.
     */
    private fun pintarCompletitud(fila: JsonObject, intereses: Int, fotos: Int) {
        val v = _binding ?: return

        var total = 0
        var cumplidos = 0
        fun contar(cumple: Boolean) {
            total++
            if (cumple) cumplidos++
        }

        contar(tieneValor(fila, "nickname"))
        contar(tieneValor(fila, "age"))
        contar(tieneValor(fila, "location"))
        contar(tieneValor(fila, "looking_for"))
        contar(tieneValor(fila, "gender"))
        contar(tieneValor(fila, "bio"))
        contar(tieneValor(fila, "height"))
        contar(tieneValor(fila, "zodiac"))
        contar(tieneValor(fila, "occupation"))
        contar(tieneValor(fila, "favorite_music"))
        contar(intereses >= 3)
        contar(fotos >= 2)
        contar(esVerdadero(fila, "verified"))

        val porcentaje = if (total == 0) 0 else (cumplidos * 100) / total
        v.tvProfileCompletion.text = getString(R.string.profile_completion, porcentaje)
        v.progressProfileCompletion.progress = porcentaje
        v.cardCompletion.visibility = View.VISIBLE
    }

    /** true si la clave existe en la fila y no está vacía / null. */
    private fun tieneValor(fila: JsonObject, clave: String): Boolean {
        val elemento = fila[clave] as? JsonPrimitive ?: return false
        if (elemento is JsonNull) return false
        return elemento.content.trim().isNotEmpty()
    }

    /** true si la columna booleana de la fila vale true. */
    private fun esVerdadero(fila: JsonObject, clave: String): Boolean {
        val elemento = fila[clave] as? JsonPrimitive ?: return false
        if (elemento is JsonNull) return false
        return elemento.content == "true"
    }

    /** Acción de la card Verificación: confirmación si ya está verificado, si no abre MyVerificationActivity. */
    private fun alPulsarVerificacion() {
        if (perfilVerificado) {
            AreloveDialog.show(
                requireContext(),
                AreloveDialog.Tipo.SUCCESS,
                getString(R.string.verification_done_title),
                getString(R.string.verification_done_msg)
            )
        } else {
            startActivity(Intent(requireContext(), MyVerificationActivity::class.java))
        }
    }

    /** Abre la pantalla legal con el tipo indicado. */
    private fun abrirLegal(tipo: String) {
        startActivity(Intent(requireContext(), LegalActivity::class.java).apply {
            putExtra("tipo", tipo)
        })
    }

    private fun compartirApp() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.profile_invite_message))
        }
        val chooser = Intent.createChooser(intent, getString(R.string.profile_invite))
        startActivity(chooser)
    }

    private fun cerrarSesion() {
        lifecycleScope.launch {
            try {
                // Borra el token FCM mientras la sesion aun esta activa
                OneSignalManager.desregistrar()
                SupabaseClient.client.auth.signOut()
                // El rol admin no sobrevive al cambio de usuario
                AdminChecker.invalidarCache()
                irAInicio()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                AdminChecker.invalidarCache()
                AreloveDialog.show(
                    requireContext(),
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    e.message.orEmpty(),
                    onPositive = { irAInicio() }
                )
            }
        }
    }

    private fun irAInicio() {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        requireActivity().finish()
    }
}