package com.arelove.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.arelove.app.databinding.ActivityProfileSetupBinding
import com.arelove.app.profile.Step1BasicFragment
import com.arelove.app.profile.Step2InterestsFragment
import com.arelove.app.profile.Step3PhotosFragment
import com.arelove.app.profile.Step4Fragment
import com.arelove.app.profile.Step5Fragment
import com.arelove.app.ui.AreloveDialog

/**
 * Asistente de creación de perfil de Arelove.
 *
 * 5 pasos en un ViewPager2 sin swipe manual:
 *  - Paso 1: datos básicos (se validan y se guardan en Supabase antes de avanzar)
 *  - Paso 2: intereses (también se guardan antes de avanzar)
 *  - Paso 3: fotos de perfil (hay que subir al menos 2)
 *  - Pasos 4 y 5: placeholders (los guardaremos más adelante)
 */
class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSetupBinding

    /** Número total de pasos del asistente. */
    private val totalPasos = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarViewPager()
        configurarBotones()
        actualizarIndicador(binding.viewPager.currentItem)
    }

    private fun configurarViewPager() {
        // Los pasos solo se cambian con los botones (sin arrastrar)
        binding.viewPager.isUserInputEnabled = false
        // Mantiene vivo el fragmento vecino para no reinflar el paso 1
        // (3 TextInputLayout + 3 Chip) al volver hacia atrás
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.adapter = SetupAdapter()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                actualizarIndicador(position)
            }
        })
    }

    private fun configurarBotones() {
        binding.btnBack.setOnClickListener {
            if (binding.viewPager.currentItem > 0) {
                binding.viewPager.currentItem -= 1
            }
        }

        binding.btnNext.setOnClickListener { avanzarPaso() }
    }

    /**
     * Lógica del botón "Siguiente" según el paso actual.
     */
    private fun avanzarPaso() {
        val actual = binding.viewPager.currentItem
        DebugLog.add("avanzarPaso: actual=$actual")

        when {
            // Paso 1: validar y guardar en Supabase antes de avanzar
            actual == 0 -> {
                // El fragmento se crea en el primer layout del ViewPager2
                val paso1 = supportFragmentManager.fragments
                    .filterIsInstance<Step1BasicFragment>()
                    .firstOrNull() ?: return

                // Si la validación falla, el fragmento marca los errores en línea
                // y no llama a ningún callback (por eso no deshabilitamos el botón)
                paso1.validarYGuardar(
                    onSuccess = { pasarSiguientePaso() },
                    onError = { mensaje ->
                        AreloveDialog.show(
                            this@ProfileSetupActivity,
                            AreloveDialog.Tipo.ERROR,
                            getString(R.string.dialog_error_title),
                            mensaje
                        )
                    }
                )
            }

            // Paso 2: validar y guardar los intereses antes de avanzar
            actual == 1 -> {
                // Cada intento empieza con el registro limpio
                DebugLog.limpiar()
                DebugLog.add("avanzarPaso: actual=$actual")
                DebugLog.add("Paso 2: buscando fragment")

                val paso2 = supportFragmentManager.fragments
                    .filterIsInstance<Step2InterestsFragment>()
                    .firstOrNull()

                if (paso2 == null) {
                    DebugLog.add("Paso 2: FRAGMENT NULL")
                    AreloveDialog.show(
                        this@ProfileSetupActivity,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.debug_title),
                        getString(R.string.debug_no_fragment)
                    )
                    return
                }

                DebugLog.add("Paso 2: fragment OK, llamando validarYGuardar")
                paso2.validarYGuardar(
                    onSuccess = {
                        DebugLog.add("Paso 2: onSuccess")
                        pasarSiguientePaso()
                    },
                    onError = { msg ->
                        DebugLog.add("Paso 2: onError=$msg")
                        AreloveDialog.show(
                            this@ProfileSetupActivity,
                            AreloveDialog.Tipo.ERROR,
                            getString(R.string.dialog_error_title),
                            msg
                        )
                    }
                )

                // TEMPORAL: 2 segundos después se pinta todas las trazas
                binding.root.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    AreloveDialog.show(
                        this@ProfileSetupActivity,
                        AreloveDialog.Tipo.INFO,
                        getString(R.string.debug_title),
                        DebugLog.texto()
                    )
                }, 2000)
            }

            // Paso 3: hace falta subir al menos 2 fotos antes de avanzar
            actual == 2 -> {
                val paso3 = supportFragmentManager.fragments
                    .filterIsInstance<Step3PhotosFragment>()
                    .firstOrNull() ?: return

                paso3.validarYGuardar(
                    onSuccess = { pasarSiguientePaso() },
                    onError = { mensaje ->
                        AreloveDialog.show(
                            this@ProfileSetupActivity,
                            AreloveDialog.Tipo.ERROR,
                            getString(R.string.dialog_error_title),
                            mensaje
                        )
                    }
                )
            }

            // Paso 5 (último): terminamos el asistente
            actual >= totalPasos - 1 -> irAHome()

            // Paso 4: por ahora solo avanzamos (lo guardaremos después)
            else -> pasarSiguientePaso()
        }
    }

    private fun pasarSiguientePaso() {
        binding.viewPager.currentItem += 1
    }

    private fun irAHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    /**
     * Actualiza el indicador "Paso X de 5", la barra de progreso y la visibilidad
     * del botón "Atrás" (oculto en el paso 1).
     */
    private fun actualizarIndicador(posicion: Int) {
        binding.tvStepIndicator.text = getString(R.string.profile_step_indicator, posicion + 1)
        binding.progressBar.progress = posicion + 1
        binding.btnBack.visibility = if (posicion == 0) View.GONE else View.VISIBLE
    }

    /** Adaptador con los 5 fragmentos del asistente. */
    private inner class SetupAdapter : FragmentStateAdapter(this@ProfileSetupActivity) {

        override fun getItemCount(): Int = totalPasos

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> Step1BasicFragment()
            1 -> Step2InterestsFragment()
            2 -> Step3PhotosFragment()
            3 -> Step4Fragment()
            else -> Step5Fragment()
        }
    }
}
