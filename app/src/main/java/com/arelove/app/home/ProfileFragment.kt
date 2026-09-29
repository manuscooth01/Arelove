package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.AreloveApp
import com.arelove.app.MainActivity
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentProfileBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

/**
 * Fragment de Perfil (placeholder con logout).
 * Muestra el email del usuario y permite cerrar la sesión.
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
        cargarEmail()
        configurarLogout()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun cargarEmail() {
        lifecycleScope.launch {
            val email = SupabaseClient.client.auth.currentUserOrNull()?.email.orEmpty()
            val sinVista = _binding ?: return@launch
            sinVista.tvEmail.text = email
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