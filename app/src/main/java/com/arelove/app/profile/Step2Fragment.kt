package com.arelove.app.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.arelove.app.R
import com.arelove.app.databinding.FragmentStep2Binding

/**
 * Placeholder del paso 2 del asistente de perfil.
 * TODO: implementar el contenido real de este paso.
 */
class Step2Fragment : Fragment() {

    private var _binding: FragmentStep2Binding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvPlaceholder.text = getString(R.string.profile_step_placeholder, 2)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
