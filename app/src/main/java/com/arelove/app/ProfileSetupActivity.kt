package com.arelove.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.arelove.app.profile.Step4OptionalFragment
import com.arelove.app.profile.Step5VerificationFragment
import com.arelove.app.ui.AreloveDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Asistente de creación de perfil de Arelove.
 *
 * 5 pasos en un ViewPager2 sin swipe manual:
 *  - Paso 1: datos básicos (se validan y se guardan en Supabase antes de avanzar)
 *  - Paso 2: intereses (también se guardan antes de avanzar)
 *  - Paso 3: fotos de perfil (hay que subir al menos 2)
 *  - Paso 4: perfil opcional (altura, peso, bio… se guarda antes de avanzar)
 *  - Paso 5: selfie de verificación (se puede saltar; termina el asistente)
 */
class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSetupBinding

    /** Preferencias donde se guarda el paso del asistente para retomarlo. */
    private lateinit var prefs: SharedPreferences

    /** Clave del paso actual dentro de las preferencias del asistente. */
    private val claveUltimoPaso = "setup_last_step"

    /** Nombre de las preferencias del asistente. */
    private val prefsName = "arelove_prefs"

    /** Número total de pasos del asistente. */
    private val totalPasos = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // El paso 5 pide avanzar cuando se pulsa "Saltar por ahora"
        supportFragmentManager.setFragmentResultListener(
            Step5VerificationFragment.CLAVE_AVANZAR,
            this
        ) { _, _ -> avanzarPaso() }

        configurarViewPager()

        // Retoma el paso donde se quedó la última vez (si sigue en rango)
        val ultimoPaso = prefs.getInt(claveUltimoPaso, -1)
        if (ultimoPaso in 0 until totalPasos) {
            binding.viewPager.currentItem = ultimoPaso
        }

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
                // Recuerda el paso para retomarlo si el usuario sale a medias
                prefs.edit().putInt(claveUltimoPaso, position).apply()
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
                val paso2 = supportFragmentManager.fragments
                    .filterIsInstance<Step2InterestsFragment>()
                    .firstOrNull() ?: return

                paso2.validarYGuardar(
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

            // Paso 4: perfil opcional (se valida y se guarda antes de avanzar)
            actual == 3 -> {
                val paso4 = supportFragmentManager.fragments
                    .filterIsInstance<Step4OptionalFragment>()
                    .firstOrNull() ?: return

                paso4.validarYGuardar(
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

            // Paso 5 (último): selfie de verificación; termina el asistente
            actual == 4 -> {
                val paso5 = supportFragmentManager.fragments
                    .filterIsInstance<Step5VerificationFragment>()
                    .firstOrNull() ?: return

                paso5.validarYGuardar(
                    onSuccess = { irAHome() },
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
        }
    }

    private fun pasarSiguientePaso() {
        binding.viewPager.currentItem += 1
    }

    private fun irAHome() {
        // Perfil terminado: el asistente ya no tiene nada que retomar
        prefs.edit().remove(claveUltimoPaso).apply()
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    /**
     * Botón "Atrás" del sistema: dentro del asistente retrocede un paso;
     * en el paso 1 pide confirmación para salir sin terminar el perfil.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val actual = binding.viewPager.currentItem
        if (actual > 0) {
            binding.viewPager.currentItem = actual - 1
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_warning_title))
                .setMessage(getString(R.string.setup_abandon_warning))
                .setPositiveButton(getString(R.string.setup_exit)) { _, _ -> finishAffinity() }
                .setNegativeButton(getString(R.string.setup_continue)) { _, _ -> }
                .show()
        }
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
            3 -> Step4OptionalFragment()
            else -> Step5VerificationFragment()
        }
    }
}
