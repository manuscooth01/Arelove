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
import com.arelove.app.ProfileSetupActivity
import com.arelove.app.R
import com.arelove.app.SettingsActivity
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentProfileBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
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

                // Cargar nickname, género y verificación desde profiles
                val filas = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list("nickname", "gender", "verified", "verification_photo_url")
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeList<kotlinx.serialization.json.JsonObject>()

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
                }

                // Cargar foto de perfil desde media
                val filasMedia = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("url")
                ) {
                    filter { eq("user_id", userId) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                    limit(1)
                }.decodeList<kotlinx.serialization.json.JsonObject>()

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
        // Tu cuenta
        binding.cardEditProfile.setOnClickListener {
            if (AntiSpam.esDobleTap("cardEditProfile")) return@setOnClickListener
            abrirAsistente(0)
        }
        binding.cardInterests.setOnClickListener {
            if (AntiSpam.esDobleTap("cardInterests")) return@setOnClickListener
            abrirAsistente(1)
        }
        binding.cardChangePhotos.setOnClickListener {
            if (AntiSpam.esDobleTap("cardChangePhotos")) return@setOnClickListener
            abrirAsistente(2)
        }
        binding.cardVerification.setOnClickListener {
            if (AntiSpam.esDobleTap("cardVerification")) return@setOnClickListener
            alPulsarVerificacion()
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
        binding.cardSettings.setOnClickListener {
            if (AntiSpam.esDobleTap("cardSettings")) return@setOnClickListener
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    /** Pinta badges de verificación del header y el estado de la card. */
    private fun pintarVerificacion(verificado: Boolean, selfieUrl: String) {
        perfilVerificado = verificado
        val v = _binding ?: return

        if (verificado) {
            v.ivVerifiedBadge.visibility = View.VISIBLE
            v.ivNameVerified.visibility = View.VISIBLE
            v.tvVerificationStatus.visibility = View.VISIBLE
            v.tvVerificationStatus.text = getString(R.string.verification_status_verified)
            v.tvVerificationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.online_green)
            )
        } else {
            v.ivVerifiedBadge.visibility = View.GONE
            v.ivNameVerified.visibility = View.GONE
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

    /** Acción de la card Verificación: confirmación si ya está verificado, si no abre el paso 5. */
    private fun alPulsarVerificacion() {
        if (perfilVerificado) {
            AreloveDialog.show(
                requireContext(),
                AreloveDialog.Tipo.SUCCESS,
                getString(R.string.verification_done_title),
                getString(R.string.verification_done_msg)
            )
        } else {
            abrirAsistente(4)
        }
    }

    /** Abre el asistente de perfil en modo edición, directamente en el paso indicado (0..4). */
    private fun abrirAsistente(paso: Int) {
        startActivity(Intent(requireContext(), ProfileSetupActivity::class.java).apply {
            putExtra(ProfileSetupActivity.EXTRA_PASO, paso)
        })
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
                SupabaseClient.client.auth.signOut()
                irAInicio()
            } catch (e: Exception) {
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