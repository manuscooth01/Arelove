package com.arelove.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.arelove.app.databinding.ActivityHomeBinding
import com.arelove.app.notifications.PushRouter
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
 * Maneja navegación desde push notifications via PushRouter.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var pushRouterJob: kotlinx.coroutines.Job? = null

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
        actualizarBadgeLikes()

        // Escuchar navegación desde push (via PushRouter)
        iniciarPushRouterListener(navController)

        // Procesar posible push al arrancar (si HomeActivity se creó por cold start)
        procesarPushInicial()
    }

    /**
     * Inicia listener del PushRouter para navegar según tipo de notificación.
     * Se ejecuta en el scope del lifecycle (se cancela al destruir Activity).
     */
    private fun iniciarPushRouterListener(navController: androidx.navigation.NavController) {
        pushRouterJob = lifecycleScope.launch {
            PushRouter.watch().consumeEach { payload ->
                when (payload) {
                    is PushRouter.PushPayload.Like -> {
                        Log.d("HomeActivity", "PushRouter: navegar a Likes")
                        navController.navigate(R.id.likesFragment)
                    }
                    is PushRouter.PushPayload.Match -> {
                        Log.d("HomeActivity", "PushRouter: navegar a Chats (matches)")
                        // Abrir tab Chats donde están los matches
                        navController.navigate(R.id.chatsFragment)
                    }
                    is PushRouter.PushPayload.Mensaje -> {
                        Log.d("HomeActivity", "PushRouter: abrir ChatActivity")
                        val intent = Intent(this@HomeActivity, com.arelove.app.home.ChatActivity::class.java).apply {
                            putExtra("chat_id", payload.chatId)
                            putExtra("otro_user_id", payload.sender)
                            payload.senderNickname?.let { putExtra("otro_nickname", it) }
                            // foto_url se hará fallback en ChatActivity
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                        }
                        startActivity(intent)
                    }
                }
            }
        }
    }

    /**
     * Procesa posible push navigation si HomeActivity se creó directamente
     * (cold start) y el intent ya trae extras.
     */
    private fun procesarPushInicial() {
        val extras = intent.extras ?: return
        val tipo = extras.getString("push_tipo") ?: return

        Log.d("HomeActivity", "Push inicial detectado: tipo=$tipo")
        PushRouter.fromExtras(extras)?.let {
            PushRouter.enrutar(it)
        }
    }

    /**
     * Maneja nuevos intents cuando la actividad ya está viva (singleTop).
     * Recibe clicks de notificación push reenviados desde MainActivity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val extras = intent.extras ?: return
        val tipo = extras.getString("push_tipo") ?: return

        Log.d("HomeActivity", "onNewIntent push: tipo=$tipo")
        PushRouter.fromExtras(extras)?.let {
            PushRouter.enrutar(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pushRouterJob?.cancel()
    }

    /**
     * Consulta los likes recibidos (sin caducar) y refresca el badge del
     * tab de Likes. Se llama al iniciar y desde LikesReceivedFragment
     * tras cada rechazo o match, para que el contador no se quede viejo.
     */
    fun actualizarBadgeLikes() {
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
                    val badge = binding.bottomNav.getOrCreateBadge(R.id.likesFragment)
                    if (countResult > 0) {
                        badge.isVisible = true
                        badge.number = countResult.toInt()
                    } else {
                        badge.isVisible = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silenciar error, solo no mostrar badge
            }
        }
    }
}