package com.arelove.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.arelove.app.databinding.ActivityHomeBinding
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pantalla principal con navegación inferior (swipe, likes, chats, perfil).
 * El logout se mueve al ProfileFragment.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.e("HomeActivity", "onCreate - inicio")

        // Precarga el cliente Supabase fuera del hilo principal
        lifecycleScope.launch(Dispatchers.IO) {
            SupabaseClient.client
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        val navController = navHostFragment.navController

        Log.e("HomeActivity", "NavController obtenido: $navController")

        binding.bottomNav.setupWithNavController(navController)

        Log.e("HomeActivity", "BottomNav setupWithNavController completado")

        // Listener de cambios de destino (temporal para diagnóstico)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val label = destination.label?.toString() ?: destination.id.toString()
            Log.e("HomeActivity", "Destino actual: $label (id=${destination.id})")
            binding.tvCurrentDestination.text = "dest: $label"
        }
    }
}