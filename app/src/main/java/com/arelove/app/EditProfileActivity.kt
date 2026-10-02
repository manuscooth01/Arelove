package com.arelove.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.databinding.ActivityEditProfileBinding
import com.arelove.app.util.AntiSpam

/**
 * Pantalla de Editar perfil: aún en construcción, muestra el aviso y
 * vuelve a la pestaña de Perfil. Aquí vivirá en el futuro la edición
 * directa de los datos sin recorrer el asistente de 5 pasos.
 */
class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseEditProfile.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseEditProfile")) return@setOnClickListener
            finish()
        }
    }
}
