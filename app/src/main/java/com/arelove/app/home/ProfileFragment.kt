package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.MainActivity
import com.arelove.app.R
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
 * Fragment de Perfil - muestra avatar circular con inicial, nombre, email, opciones y botón logout.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

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
        cargarPerfil()
        configurarLogout()
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

                // Cargar nickname y foto desde profiles y media
                val filas = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list("nickname", "gender")
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
                    if (genderRes != null) {
                        sinVista.tvGender.text = getString(genderRes)
                        sinVista.tvGender.visibility = View.VISIBLE
                    } else {
                        sinVista.tvGender.visibility = View.GONE
                    }
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