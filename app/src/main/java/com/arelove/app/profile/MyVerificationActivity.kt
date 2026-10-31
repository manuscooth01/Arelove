package com.arelove.app.profile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.R
import com.arelove.app.databinding.ActivityMyVerificationBinding
import com.arelove.app.util.AntiSpam

/**
 * Pantalla Verificación de identidad: la selfie se gestiona aquí sin pasar
 * por el asistente de 5 pasos.
 *
 * Aloja [Step5VerificationFragment] en modo edición (cámara, galería, preview
 * y estado de la verificación son los mismos que en el Paso 5). No hay botón
 * de avanzar: la pantalla se cierra con la X, también tras subir la selfie.
 */
class MyVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyVerificationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseMyVerification.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseMyVerification")) return@setOnClickListener
            finish()
        }

        // En rotación el FragmentManager ya restaura la instancia
        if (supportFragmentManager.findFragmentById(R.id.containerMyVerification) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.containerMyVerification, Step5VerificationFragment.modoEdicion())
                .commit()
        }
    }
}
