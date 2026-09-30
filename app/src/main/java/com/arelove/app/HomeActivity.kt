package com.arelove.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.arelove.app.databinding.ActivityHomeBinding
import com.arelove.app.util.AntiSpam
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.material.badge.BadgeDrawable

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

        // Precarga el cliente Supabase fuera del hilo principal
        lifecycleScope.launch(Dispatchers.IO) {
            SupabaseClient.client
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)

        // Configurar badge en el tab de Likes
        configurarBadgeLikes()
    }

    private fun configurarBadgeLikes() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val userId = session?.user?.id
                if (userId == null) return@launch

                // SELECT count(*) FROM likes WHERE to_user = currentUserId AND expires_at > now()
                val countResult = SupabaseClient.client.postgrest["likes"]
                    .select(head = true) {
                        filter { eq("to_user", userId) }
                        filter { gte("expires_at", java.time.Instant.now().toString()) }
                        count(io.github.jan.supabase.postgrest.query.Count.EXACT)
                    }
                    .countOrNull() ?: 0

                runOnUiThread {
                    val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_likes)
                    if (countResult > 0) {
                        badge.isVisible = true
                        badge.number = countResult
                    } else {
                        badge.isVisible = false
                    }
                }
            } catch (e: Exception) {
                // Silenciar error, solo no mostrar badge
            }
        }
    }