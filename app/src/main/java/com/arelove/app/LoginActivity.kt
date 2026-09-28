package com.arelove.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.databinding.ActivityLoginBinding

/**
 * Pantalla de inicio de sesión de Arelove.
 * Solo diseño visual: la autenticación se implementará más adelante.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotones()
    }

    private fun configurarBotones() {
        binding.btnLogin.setOnClickListener {
            // TODO: validar credenciales con Supabase Auth
            Toast.makeText(this, R.string.login_coming_soon, Toast.LENGTH_SHORT).show()
        }

        binding.tvGoRegister.setOnClickListener {
            // TODO: abrir la pantalla de registro
            finish()
        }
    }
}
