package com.arelove.app.home

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.arelove.app.ErrorSanitizer
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityProfileDetailBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import coil.transform.RoundedCornersTransformation
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Activity de detalle de perfil (otro usuario).
 * Muestra fotos, info principal, intereses y datos opcionales.
 */
class ProfileDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileDetailBinding
    private var userId: String = ""
    private val fotos = mutableListOf<String>()
    private var currentPage = 0
    private var profileData: JsonObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra("user_id") ?: ""
        if (userId.isEmpty()) {
            finish()
            return
        }

        configurarBotones()
        cargarPerfil()
    }

    private fun configurarBotones() {
        binding.btnClose.setOnClickListener {
            if (AntiSpam.esDobleTap("btnClose")) return@setOnClickListener
            finish()
        }
    }

    private fun cargarPerfil() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cargar perfil completo
                val filas = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list("nickname", "age", "location", "looking_for", "bio", "verified", "height", "zodiac", "occupation", "favorite_music", "gender")
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeList<JsonObject>()

                if (filas.isNotEmpty()) {
                    profileData = filas[0]
                    runOnUiThread { mostrarPerfil() }
                }

                // Cargar fotos
                val filasMedia = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("url")
                ) {
                    filter { eq("user_id", userId) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                    limit(6)
                }.decodeList<JsonObject>()

                val urls = filasMedia.mapNotNull { (it["url"] as? JsonPrimitive)?.content }.filter { it.isNotEmpty() }
                runOnUiThread {
                    fotos.clear()
                    fotos.addAll(urls)
                    setupPhotoPager()
                }

                // Cargar intereses
                val filasInterests = SupabaseClient.client.postgrest["user_interests"].select(
                    columns = Columns.list("interest_id")
                ) {
                    filter { eq("user_id", userId) }
                }.decodeList<JsonObject>()

                if (filasInterests.isNotEmpty()) {
                    val interestIds = filasInterests.mapNotNull { (it["interest_id"] as? JsonPrimitive)?.content }
                    if (interestIds.isNotEmpty()) {
                        cargarNombresIntereses(interestIds)
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileDetailActivity", "Error cargando perfil", e)
                runOnUiThread {
                    AreloveDialog.show(
                        this@ProfileDetailActivity,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.dialog_error_title),
                        getString(R.string.error_generic),
                        onPositive = { finish() }
                    )
                }
            }
        }
    }

    private fun mostrarPerfil() {
        val data = profileData ?: return
        val b = binding

        val nickname = (data["nickname"] as? JsonPrimitive)?.content.orEmpty()
        val age = data["age"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val location = (data["location"] as? JsonPrimitive)?.content.orEmpty()
        val bio = (data["bio"] as? JsonPrimitive)?.content.orEmpty()
        val verified = data["verified"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
        val height = data["height"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val zodiac = (data["zodiac"] as? JsonPrimitive)?.content.orEmpty()
        val occupation = (data["occupation"] as? JsonPrimitive)?.content.orEmpty()
        val favoriteMusic = (data["favorite_music"] as? JsonPrimitive)?.content.orEmpty()
        val gender = (data["gender"] as? JsonPrimitive)?.content.orEmpty()

        b.tvName.text = buildNameString(nickname, age)
        b.ivVerified.visibility = if (verified) View.VISIBLE else View.GONE

        // "Busca pareja/amistades/ambos"
        val lookingFor = (data["looking_for"] as? JsonPrimitive)?.content.orEmpty()
        val lookingForRes = when (lookingFor) {
            "pareja" -> R.string.profile_detail_looking_pareja
            "amistades" -> R.string.profile_detail_looking_amistades
            "ambos" -> R.string.profile_detail_looking_ambos
            else -> null
        }
        if (lookingForRes != null) {
            b.tvLookingFor.text = getString(lookingForRes)
            b.tvLookingFor.visibility = View.VISIBLE
        } else {
            b.tvLookingFor.visibility = View.GONE
        }

        // Género
        val genderRes = when (gender) {
            "hombre" -> R.string.gender_hombre
            "mujer" -> R.string.gender_mujer
            "otro" -> R.string.gender_otro
            else -> null
        }
        if (genderRes != null) {
            b.tvGender.text = getString(genderRes)
            b.tvGender.visibility = View.VISIBLE
        } else {
            b.tvGender.visibility = View.GONE
        }

        b.tvLocation.text = location
        b.tvBio.text = bio

        // Datos opcionales
        val hasOptional = height != null || zodiac.isNotEmpty() || occupation.isNotEmpty() || favoriteMusic.isNotEmpty()
        b.llOptionalInfo.visibility = if (hasOptional) View.VISIBLE else View.GONE

        if (hasOptional) {
            b.llOptionalFields.removeAllViews()
            if (height != null) agregarCampoOpcional(getString(R.string.profile_detail_height), "${height} cm")
            if (zodiac.isNotEmpty()) agregarCampoOpcional(getString(R.string.profile_detail_zodiac), zodiac)
            if (occupation.isNotEmpty()) agregarCampoOpcional(getString(R.string.profile_detail_occupation), occupation)
            if (favoriteMusic.isNotEmpty()) agregarCampoOpcional(getString(R.string.profile_detail_favorite_music), favoriteMusic)
        }
    }

    private fun agregarCampoOpcional(label: String, valor: String) {
        val b = binding
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, 0, 16, 0)
        }

        val tvValor = TextView(this).apply {
            text = valor
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        container.addView(tvLabel)
        container.addView(tvValor)
        b.llOptionalFields.addView(container)
    }

    private fun cargarNombresIntereses(interestIds: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val placeholders = interestIds.map { "?" }.joinToString(",")
                // Nota: Supabase no soporta IN directamente en el builder, así que hacemos queries individuales
                val nombres = mutableListOf<String>()
                for (id in interestIds) {
                    val filas = SupabaseClient.client.postgrest["interests"].select(
                        columns = Columns.list("name")
                    ) {
                        filter { eq("id", id) }
                        limit(1)
                    }.decodeList<JsonObject>()

                    if (filas.isNotEmpty()) {
                        val name = (filas[0]["name"] as? JsonPrimitive)?.content.orEmpty()
                        if (name.isNotEmpty()) nombres.add(name)
                    }
                }

                if (nombres.isNotEmpty()) {
                    runOnUiThread { mostrarIntereses(nombres) }
                }
            } catch (e: Exception) {
                Log.e("ProfileDetailActivity", "Error cargando intereses", e)
            }
        }
    }

    private fun mostrarIntereses(nombres: List<String>) {
        val b = binding
        b.llInterests.visibility = View.VISIBLE
        b.cgInterests.removeAllViews()

        for (nombre in nombres) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = nombre
                isClickable = false
                setChipBackgroundColorResource(R.color.primary_container)
                setTextColor(getColor(R.color.on_primary_container))
            }
            b.cgInterests.addView(chip)
        }
    }

    private fun buildNameString(nickname: String, age: Int?): String {
        return "$nickname${age?.let { ", $it" } ?: ""}"
    }

    private fun setupPhotoPager() {
        val b = binding
        val adapter = PhotoPagerAdapter(fotos)
        b.photoPager.adapter = adapter

        actualizarIndicadores(fotos.size, 0)

        b.photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                actualizarIndicadores(fotos.size, position)
            }
        })
    }

    private fun actualizarIndicadores(total: Int, actual: Int) {
        val b = binding
        b.llIndicators.removeAllViews()

        if (total <= 1) {
            b.llIndicators.visibility = View.GONE
            return
        }
        b.llIndicators.visibility = View.VISIBLE

        val tamanoPx = (8 * resources.displayMetrics.density).toInt()
        val margenPx = (4 * resources.displayMetrics.density).toInt()

        repeat(total) { i ->
            val dot = ImageView(this)
            val lp = LinearLayout.LayoutParams(
                (8 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt()
            )
            lp.marginStart = (4 * resources.displayMetrics.density).toInt()
            lp.marginEnd = (4 * resources.displayMetrics.density).toInt()
            dot.layoutParams = lp
            dot.setImageResource(
                if (i == actual) R.drawable.indicator_dot_active
                else R.drawable.indicator_dot_inactive
            )
            b.llIndicators.addView(dot)
        }
    }

    private inner class PhotoPagerAdapter(private val urls: List<String>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val imageView: androidx.appcompat.widget.AppCompatImageView = view.findViewById(R.id.ivPhotoPage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_photo_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            if (urls.isEmpty()) {
                holder.imageView.setImageResource(R.color.photo_placeholder)
            } else {
                val url = urls[position % urls.size]
                holder.imageView.load(url) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(20f, 0f))
                    placeholder(R.color.photo_placeholder)
                    error(R.color.photo_placeholder)
                }
            }
        }

        override fun getItemCount(): Int = urls.size.coerceAtLeast(1)
    }
}