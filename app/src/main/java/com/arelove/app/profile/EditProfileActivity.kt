package com.arelove.app.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.arelove.app.ErrorSanitizer
import com.arelove.app.LogHelper
import com.arelove.app.LocationHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.ActivityEditProfileBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pantalla de Editar perfil: formulario con los mismos campos que el asistente
 * (datos del Paso 1, intereses del Paso 2 y datos opcionales del Paso 4) para
 * modificar el perfil sin recorrer los 5 pasos.
 *
 * - Al abrir carga la fila propia de `profiles`, la primera foto de `media`
 *   (el avatar), el catálogo de `interests` y la selección de `user_interests`.
 * - Al guardar: un único UPDATE en `profiles` (filtro por el id de la sesión)
 *   más el reemplazo de los intereses (DELETE + INSERT).
 * - "Cambiar foto" abre [MyPhotosActivity]: el avatar se calcula como la foto
 *   más antigua de `media`, así que las fotos se añaden o borran allí.
 */
class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding

    /** Evita guardar dos veces si pulsan "Guardar cambios" repetidamente. */
    private var guardando = false

    /** Evita recargar el perfil mientras ya se está cargando. */
    private var cargando = false

    /** Evita recargar el catálogo de intereses mientras ya se está cargando. */
    private var cargandoIntereses = false

    /** Marca que la fila de `profiles` ya se cargó (para refrescar el avatar). */
    private var perfilCargado = false

    /** Apodo actual, para pintar la inicial del avatar cuando no hay foto. */
    private var nicknameActual: String? = null

    /** chipId → interestId, para traducir la selección de la vista al guardar. */
    private val interesesPorChip = mutableMapOf<Int, String>()

    /** Fecha elegida, para reabrir el calendario en ese día. */
    private var fechaNacimiento: LocalDate? = null

    /** Coordenadas del GPS; null si el lugar se escribió a mano. */
    private var coordenadas: LocationHelper.Coordenadas? = null

    /** Evita borrar las coordenadas cuando rellenamos el campo desde el GPS. */
    private var rellenandoLugar = false

    /** Selector de fecha cacheado (se reconstruye si ya se usó). */
    private var datePicker: MaterialDatePicker<Long>? = null

    /** Marca si el selector actual ya se mostró (no se reutiliza tras usarse). */
    private var selectorUsado = false

    /** Pide permisos de ubicación al pulsar "Usar mi ubicación". */
    private val solicitudPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        if (resultados.values.any { it }) {
            obtenerUbicacion()
        } else {
            avisarPermisoDenegado()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarCiudades()
        configurarChips()
        configurarFechaNacimiento()
        configurarLugar()
        configurarBotones()

        cargarPerfil()
        cargarIntereses()
    }

    override fun onResume() {
        super.onResume()
        // Volviendo de "Mis fotos": la foto principal puede haber cambiado
        if (perfilCargado) cargarAvatar()
    }

    // ---------------------------------------------------------------------
    // Configuración de la vista
    // ---------------------------------------------------------------------

    private fun configurarBotones() {
        binding.btnCloseEditProfile.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseEditProfile")) return@setOnClickListener
            finish()
        }

        // El avatar es la foto más antigua de `media`: se gestionan ahí
        binding.btnChangePhoto.setOnClickListener {
            if (AntiSpam.esDobleTap("btnChangePhoto")) return@setOnClickListener
            startActivity(Intent(this, MyPhotosActivity::class.java))
        }

        binding.btnSaveProfile.setOnClickListener {
            if (AntiSpam.esDobleTap("btnSaveProfile")) return@setOnClickListener
            validarYGuardar()
        }

        binding.btnUseLocation.setOnClickListener {
            if (AntiSpam.esDobleTap("btnUseLocation")) return@setOnClickListener
            pedirUbicacion()
        }
    }

    /** Sugerencias de ciudades más comunes de Venezuela (material). */
    private fun configurarCiudades() {
        binding.etLocation.setSimpleItems(resources.getStringArray(R.array.venezuela_cities))
        binding.etZodiac.setSimpleItems(resources.getStringArray(R.array.zodiac_signs))
    }

    /** Quita los errores en cuanto el usuario escribe o elige una opción. */
    private fun configurarChips() {
        binding.etNickname.doAfterTextChanged { binding.tilNickname.error = null }
        binding.etBirthdate.doAfterTextChanged { binding.tilBirthdate.error = null }
        binding.etHeight.doAfterTextChanged { binding.tilHeight.error = null }
        binding.etWeight.doAfterTextChanged { binding.tilWeight.error = null }
        binding.etBio.doAfterTextChanged { binding.tilBio.error = null }
        binding.etOccupation.doAfterTextChanged { binding.tilOccupation.error = null }
        binding.etFavoriteMusic.doAfterTextChanged { binding.tilFavoriteMusic.error = null }
        binding.etZodiac.doAfterTextChanged { binding.tilZodiac.error = null }

        binding.chipGroupLooking.setOnCheckedStateChangeListener { _, idsSeleccionados ->
            if (idsSeleccionados.isNotEmpty()) binding.tvLookingError.visibility = View.GONE
        }

        binding.chipGroupGender.setOnCheckedStateChangeListener { _, idsSeleccionados ->
            if (idsSeleccionados.isNotEmpty()) binding.tvGenderError.visibility = View.GONE
        }

        binding.chipGroupInterests.setOnCheckedStateChangeListener { _, idsSeleccionados ->
            actualizarContadorIntereses(idsSeleccionados.size)
            if (idsSeleccionados.size >= MINIMO_INTERESES) {
                binding.tvInterestsError.visibility = View.GONE
            }
        }
    }

    /** Abre el calendario al tocar el campo o su ícono. */
    private fun configurarFechaNacimiento() {
        binding.etBirthdate.setOnClickListener { mostrarSelectorFecha() }
        binding.tilBirthdate.setEndIconOnClickListener { mostrarSelectorFecha() }
    }

    /** Campo de lugar + botón de ubicación por GPS. */
    private fun configurarLugar() {
        binding.etLocation.doAfterTextChanged {
            if (!rellenandoLugar) coordenadas = null
            binding.tilLocation.error = null
        }
    }

    // ---------------------------------------------------------------------
    // Ubicación (GPS)
    // ---------------------------------------------------------------------

    /** Pide permisos si hacen falta, o ya obtiene la ubicación. */
    private fun pedirUbicacion() {
        if (tienePermisoUbicacion()) {
            obtenerUbicacion()
        } else {
            solicitudPermisos.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun tienePermisoUbicacion(): Boolean {
        val fino = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val grueso = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fino || grueso
    }

    /** Rellena el campo de lugar con la ciudad detectada por el GPS. */
    private fun obtenerUbicacion() {
        lifecycleScope.launch {
            try {
                val ubicacion = LocationHelper.obtenerUbicacionActual(this@EditProfileActivity)
                val ciudad = ubicacion.ciudad

                if (ciudad != null) {
                    coordenadas = LocationHelper.Coordenadas(
                        latitude = ubicacion.latitude,
                        longitude = ubicacion.longitude
                    )
                }

                if (ciudad == null) {
                    mostrarDialogo(
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.location_error)
                    )
                    return@launch
                }

                rellenandoLugar = true
                binding.etLocation.setText(ciudad)
                rellenandoLugar = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: SecurityException) {
                mostrarDialogo(
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.location_permission_denied)
                )
            } catch (e: Exception) {
                LogHelper.e(TAG_LOG, "Error obteniendo la ubicación")
                mostrarDialogo(
                    AreloveDialog.Tipo.ERROR,
                    ErrorSanitizer.limpiar(e.message ?: getString(R.string.location_error))
                )
            }
        }
    }

    /** Avisa si el permiso se denegó; invita a abrir Ajustes si ya no se puede pedir. */
    private fun avisarPermisoDenegado() {
        val sinJustificacion =
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (sinJustificacion) {
            mostrarDialogo(
                AreloveDialog.Tipo.WARNING,
                getString(R.string.location_permission_settings),
                alAceptar = { abrirAjustesDeLaApp() }
            )
        } else {
            mostrarDialogo(
                AreloveDialog.Tipo.WARNING,
                getString(R.string.location_permission_denied)
            )
        }
    }

    /** Abre la ficha de permisos de esta app en los Ajustes del teléfono. */
    private fun abrirAjustesDeLaApp() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // ---------------------------------------------------------------------
    // Selector de fecha de nacimiento
    // ---------------------------------------------------------------------

    /**
     * Muestra un [MaterialDatePicker] limitado a fechas pasadas (hoy - 100
     * años … hoy) y cachea el selector: MaterialDatePicker no permite cambiar
     * la selección una vez construido.
     */
    private fun mostrarSelectorFecha() {
        val selectorPreparado = datePicker
        if (selectorPreparado == null || selectorUsado ||
            selectorPreparado.selection != millisUtc(fechaInicial())
        ) {
            datePicker = construirSelector()
        }

        selectorUsado = true
        datePicker?.show(supportFragmentManager, TAG_SELECTOR_FECHA)
    }

    /** Fecha con la que abre el calendario: la elegida, la del campo o la sugerida. */
    private fun fechaInicial(): LocalDate {
        val hoy = LocalDate.now()
        val minima = hoy.minusYears(EDAD_MAXIMA.toLong())
        val candidata = fechaNacimiento
            ?: parsearFecha(binding.etBirthdate.text?.toString().orEmpty())
            ?: hoy.minusYears(EDAD_SUGERIDA.toLong())

        return candidata.coerceIn(minima, hoy)
    }

    /** Restricciones del calendario: solo fechas pasadas (hoy - 100 años … hoy). */
    private fun crearConstraints(): CalendarConstraints {
        val hoy = LocalDate.now()
        val minima = hoy.minusYears(EDAD_MAXIMA.toLong())

        return CalendarConstraints.Builder()
            .setStart(millisUtc(minima))
            .setEnd(millisUtc(hoy))
            .setOpenAt(millisUtc(fechaInicial()))
            .setValidator(
                CompositeDateValidator.allOf(
                    listOf<CalendarConstraints.DateValidator>(
                        DateValidatorPointForward.from(millisUtc(minima)),
                        DateValidatorPointBackward.before(millisUtc(hoy))
                    )
                )
            )
            .build()
    }

    /** Construye el selector y engancha el callback que escribe la fecha elegida. */
    private fun construirSelector(): MaterialDatePicker<Long> {
        val selector = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.field_birthdate)
            .setCalendarConstraints(crearConstraints())
            .setSelection(millisUtc(fechaInicial()))
            .build()

        selector.addOnPositiveButtonClickListener { seleccion ->
            val elegida = Instant.ofEpochMilli(seleccion).atZone(ZoneOffset.UTC).toLocalDate()
            aplicarFechaNacimiento(elegida)
        }

        return selector
    }

    /** Escribe la fecha en el campo y marca el error si la edad no es válida. */
    private fun aplicarFechaNacimiento(fecha: LocalDate) {
        val edad = Period.between(fecha, LocalDate.now()).years

        fechaNacimiento = fecha
        binding.etBirthdate.setText(FORMATO_FECHA.format(fecha))
        binding.tilBirthdate.error = when {
            edad < EDAD_MINIMA -> getString(R.string.validation_min_age)
            edad > EDAD_MAXIMA -> getString(R.string.validation_invalid_birthdate)
            else -> null
        }
    }

    /** millis en UTC a medianoche, como espera MaterialDatePicker. */
    private fun millisUtc(fecha: LocalDate): Long =
        fecha.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Convierte "dd/MM/yyyy" a [LocalDate], o null si el texto no es válido. */
    private fun parsearFecha(texto: String): LocalDate? = try {
        LocalDate.parse(texto, FORMATO_FECHA)
    } catch (e: DateTimeParseException) {
        null
    }

    // ---------------------------------------------------------------------
    // Carga de los datos ya guardados
    // ---------------------------------------------------------------------

    /**
     * SELECT nickname, birthdate, location, looking_for, gender, latitude,
     *        longitude, bio, height, weight, zodiac, occupation, favorite_music
     *     FROM profiles WHERE id = userId LIMIT 1
     *
     * Si falla no se corta la edición: el usuario puede corregir los campos a
     * mano y reintentar el guardado.
     */
    private fun cargarPerfil() {
        if (cargando) return
        cargando = true
        binding.pbEditProfile.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina (nunca en el hilo principal)
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                    ?: return@launch

                val fila = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list(
                        "nickname", "birthdate", "location", "looking_for", "gender",
                        "latitude", "longitude", "bio", "height", "weight", "zodiac",
                        "occupation", "favorite_music"
                    )
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeSingleOrNull<JsonObject>() ?: return@launch

                val b = binding

                b.etNickname.setText(textoDe(fila, "nickname"))
                nicknameActual = textoDe(fila, "nickname")

                // Fecha de nacimiento: ISO yyyy-MM-dd en BD → dd/MM/yyyy en pantalla
                textoDe(fila, "birthdate")?.let { iso ->
                    try {
                        val fecha = LocalDate.parse(iso)
                        fechaNacimiento = fecha
                        b.etBirthdate.setText(FORMATO_FECHA.format(fecha))
                    } catch (e: DateTimeParseException) {
                        LogHelper.e(TAG_LOG, "Error parseando birthdate")
                    }
                }

                // Lugar: sin disparar el filtro del desplegable
                textoDe(fila, "location")?.let { lugar ->
                    rellenandoLugar = true
                    b.etLocation.setText(lugar, false)
                    rellenandoLugar = false
                }

                // Coordenadas guardadas: se restauran para no perderlas
                val lat = numeroDe(fila, "latitude")
                val lng = numeroDe(fila, "longitude")
                if (lat != null && lng != null) {
                    coordenadas = LocationHelper.Coordenadas(latitude = lat, longitude = lng)
                }

                when (textoDe(fila, "looking_for")) {
                    "pareja" -> b.chipPareja.isChecked = true
                    "amistades" -> b.chipAmistades.isChecked = true
                    "ambos" -> b.chipAmbos.isChecked = true
                }

                when (textoDe(fila, "gender")) {
                    "hombre" -> b.chipHombre.isChecked = true
                    "mujer" -> b.chipMujer.isChecked = true
                    "otro" -> b.chipOtro.isChecked = true
                    "prefiero_no_decir" -> b.chipPrefieroNoDecir.isChecked = true
                }

                b.etBio.setText(textoDe(fila, "bio") ?: "")
                b.etHeight.setText(textoDe(fila, "height") ?: "")
                b.etWeight.setText(textoDe(fila, "weight") ?: "")
                b.etZodiac.setText(textoDe(fila, "zodiac") ?: "", false)
                b.etOccupation.setText(textoDe(fila, "occupation") ?: "")
                b.etFavoriteMusic.setText(textoDe(fila, "favorite_music") ?: "")

                perfilCargado = true
                cargarAvatar()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e(TAG_LOG, "Error cargando el perfil para editar")
                if (activa) {
                    mostrarDialogo(
                        AreloveDialog.Tipo.ERROR,
                        getString(
                            R.string.profile_error_save_detail,
                            getString(R.string.edit_profile_load_error),
                            ErrorSanitizer.limpiar(e.message ?: e.toString())
                        ),
                        alAceptar = { cargarPerfil() }
                    )
                }
            } finally {
                cargando = false
                if (activa) binding.pbEditProfile.visibility = View.GONE
            }
        }
    }

    /**
     * Pinta la foto principal de `media` (la más antigua, que es la que se
     * muestra como avatar en el resto de la app) o la inicial del apodo.
     */
    private fun cargarAvatar() {
        lifecycleScope.launch {
            try {
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                    ?: return@launch

                val fila = SupabaseClient.client.postgrest["media"].select(
                    columns = Columns.list("url")
                ) {
                    filter { eq("user_id", userId) }
                    filter { eq("type", "profile") }
                    order("created_at", Order.ASCENDING)
                    limit(1)
                }.decodeSingleOrNull<JsonObject>()

                val url = (fila?.get("url") as? JsonPrimitive)
                    ?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()

                if (!activa) return@launch

                if (url.isNotEmpty()) {
                    binding.ivEditAvatar.load(url) {
                        crossfade(true)
                    }
                    binding.tvEditAvatarInitial.visibility = View.GONE
                } else {
                    // Sin foto: inicial del apodo sobre el círculo de marca
                    binding.ivEditAvatar.setImageDrawable(null)
                    val inicial = nicknameActual?.firstOrNull()?.uppercase()
                    if (inicial != null) {
                        binding.tvEditAvatarInitial.text = inicial
                        binding.tvEditAvatarInitial.visibility = View.VISIBLE
                    } else {
                        binding.tvEditAvatarInitial.visibility = View.GONE
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e(TAG_LOG, "Error cargando el avatar")
            }
        }
    }

    /** SELECT * FROM interests ORDER BY name ASC, y preselección de los guardados. */
    private fun cargarIntereses() {
        if (cargandoIntereses) return
        cargandoIntereses = true

        binding.pbInterests.visibility = View.VISIBLE
        binding.chipGroupInterests.removeAllViews()
        interesesPorChip.clear()
        actualizarContadorIntereses(0)

        lifecycleScope.launch {
            try {
                val catalogo = SupabaseClient.client.postgrest["interests"].select {
                    order("name", Order.ASCENDING)
                }
                val intereses = catalogo.decodeList<JsonObject>()
                val mias = interesesGuardados()

                if (!activa) return@launch
                binding.pbInterests.visibility = View.GONE
                rellenarChips(intereses, mias)
                actualizarContadorIntereses(binding.chipGroupInterests.checkedChipIds.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e(TAG_LOG, "Error cargando intereses")

                if (activa) {
                    binding.pbInterests.visibility = View.GONE
                    AreloveDialog.show(
                        this@EditProfileActivity,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.dialog_error_title),
                        getString(
                            R.string.profile_error_save_detail,
                            getString(R.string.error_load_interests),
                            ErrorSanitizer.limpiar(e.message ?: e.toString())
                        ),
                        // Al aceptar volvemos a intentar la carga
                        onPositive = { cargarIntereses() }
                    )
                }
            } finally {
                cargandoIntereses = false
            }
        }
    }

    /** Intereses ya marcados: SELECT interest_id FROM user_interests. */
    private suspend fun interesesGuardados(): Set<String> {
        val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id ?: return emptySet()

        val resultado = SupabaseClient.client.postgrest["user_interests"].select {
            filter { eq("user_id", userId) }
        }

        return resultado.decodeList<JsonObject>()
            .mapNotNull { (it["interest_id"] as? JsonPrimitive)?.content }
            .toSet()
    }

    /** Crea un chip por interés y marca los que ya estaban guardados. */
    private fun rellenarChips(intereses: List<JsonObject>, guardados: Set<String>) {
        intereses.forEach { interes ->
            val interestId = (interes["id"] as? JsonPrimitive)?.content ?: return@forEach
            val nombre = (interes["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (nombre.isEmpty()) return@forEach

            val chip = Chip(this).apply {
                text = nombre
                isCheckable = true
                isCheckedIconVisible = true
                // Llamada explícita: la propiedad sintética chipIconVisible no resuelve
                // (Chip declara setChipIconVisible(int) y setChipIconVisible(boolean)).
                setChipIconVisible(false)
                id = View.generateViewId()
            }

            interesesPorChip[chip.id] = interestId
            binding.chipGroupInterests.addView(chip)

            if (interestId in guardados) {
                binding.chipGroupInterests.check(chip.id)
            }
        }
    }

    /** Escribe el contador "X de 3 seleccionados" con sus plurales. */
    private fun actualizarContadorIntereses(total: Int) {
        binding.tvSelectionCount.text = if (total < MINIMO_INTERESES) {
            resources.getQuantityString(R.plurals.profile_step2_counter_min, total, total)
        } else {
            resources.getQuantityString(R.plurals.profile_step2_counter_ok, total, total)
        }
    }

    // ---------------------------------------------------------------------
    // Validación y guardado
    // ---------------------------------------------------------------------

    /** Opción seleccionada en los chips "Busco", o null si no hay ninguna. */
    private fun buscandoSeleccionado(): String? = when (binding.chipGroupLooking.checkedChipId) {
        R.id.chipPareja -> "pareja"
        R.id.chipAmistades -> "amistades"
        R.id.chipAmbos -> "ambos"
        else -> null
    }

    /** Género seleccionado en los chips, o null si no hay ninguno. */
    private fun generoSeleccionado(): String? = when (binding.chipGroupGender.checkedChipId) {
        R.id.chipHombre -> "hombre"
        R.id.chipMujer -> "mujer"
        R.id.chipOtro -> "otro"
        R.id.chipPrefieroNoDecir -> "prefiero_no_decir"
        else -> null
    }

    /**
     * Valida el formulario completo y guarda: UPDATE de la fila propia en
     * `profiles` (con la edad recalculada) y reemplazo de los intereses.
     */
    private fun validarYGuardar() {
        val b = binding

        // Limpiamos los errores de la validación anterior
        b.tilNickname.error = null
        b.tilBirthdate.error = null
        b.tilLocation.error = null
        b.tilHeight.error = null
        b.tilWeight.error = null
        b.tvLookingError.visibility = View.GONE
        b.tvGenderError.visibility = View.GONE
        b.tvInterestsError.visibility = View.GONE

        val nickname = b.etNickname.text?.toString()?.trim().orEmpty()
        val fechaTexto = b.etBirthdate.text?.toString()?.trim().orEmpty()
        val location = b.etLocation.text?.toString()?.trim().orEmpty()
        val bio = b.etBio.text?.toString()?.trim().orEmpty()
        val alturaTexto = b.etHeight.text?.toString()?.trim().orEmpty()
        val pesoTexto = b.etWeight.text?.toString()?.trim().orEmpty()
        val ocupacion = b.etOccupation.text?.toString()?.trim().orEmpty()
        val musica = b.etFavoriteMusic.text?.toString()?.trim().orEmpty()
        val signo = b.etZodiac.text?.toString()?.trim().orEmpty()
        val buscando = buscandoSeleccionado()
        val genero = generoSeleccionado()

        if (nickname.isEmpty()) {
            b.tilNickname.error = getString(R.string.required_field)
            return
        }

        if (fechaTexto.isEmpty()) {
            b.tilBirthdate.error = getString(R.string.required_field)
            return
        }

        val fecha = parsearFecha(fechaTexto)
        if (fecha == null) {
            b.tilBirthdate.error = getString(R.string.validation_invalid_birthdate)
            return
        }

        val edad = Period.between(fecha, LocalDate.now()).years
        if (edad < EDAD_MINIMA) {
            b.tilBirthdate.error = getString(R.string.validation_min_age)
            return
        }

        if (edad > EDAD_MAXIMA) {
            b.tilBirthdate.error = getString(R.string.validation_invalid_birthdate)
            return
        }

        if (location.isEmpty()) {
            b.tilLocation.error = getString(R.string.required_field)
            return
        }

        if (buscando == null) {
            b.tvLookingError.text = getString(R.string.profile_error_looking_for)
            b.tvLookingError.visibility = View.VISIBLE
            return
        }

        if (genero == null) {
            b.tvGenderError.text = getString(R.string.error_gender_required)
            b.tvGenderError.visibility = View.VISIBLE
            return
        }

        // Altura opcional: si trae valor, debe estar entre 100 y 250 cm
        val altura = if (alturaTexto.isEmpty()) null else alturaTexto.toIntOrNull()
        if (alturaTexto.isNotEmpty() && (altura == null || altura !in ALTURA_MINIMA..ALTURA_MAXIMA)) {
            b.tilHeight.error = getString(R.string.validation_height_range)
            return
        }

        // Peso opcional: si trae valor, debe estar entre 30 y 300 kg
        val peso = if (pesoTexto.isEmpty()) null else pesoTexto.toIntOrNull()
        if (pesoTexto.isNotEmpty() && (peso == null || peso !in PESO_MINIMO..PESO_MAXIMO)) {
            b.tilWeight.error = getString(R.string.validation_weight_range)
            return
        }

        val intereses = b.chipGroupInterests.checkedChipIds.mapNotNull { interesesPorChip[it] }
        if (intereses.size < MINIMO_INTERESES) {
            b.tvInterestsError.text = getString(R.string.profile_step2_error_min)
            b.tvInterestsError.visibility = View.VISIBLE
            return
        }

        if (guardando) return
        guardando = true
        b.btnSaveProfile.isEnabled = false

        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina: en el hilo principal
                // currentSessionOrNull() puede bloquear y congelar la pantalla
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                if (userId == null) {
                    mostrarDialogo(
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.profile_error_no_session)
                    )
                    return@launch
                }

                val coords = coordenadas
                val fila = buildJsonObject {
                    put("nickname", nickname)
                    put("age", edad)
                    put("birthdate", fecha.toString()) // ISO: YYYY-MM-DD
                    put("location", location)
                    put("looking_for", buscando)
                    put("gender", genero)

                    if (coords != null) {
                        put("latitude", coords.latitude)
                        put("longitude", coords.longitude)
                    } else {
                        // Escrito a mano: limpiamos cualquier coordenada anterior
                        put("latitude", JsonNull)
                        put("longitude", JsonNull)
                    }

                    ponerTexto("bio", bio)
                    ponerNumero("height", altura)
                    ponerNumero("weight", peso)
                    ponerTexto("zodiac", signo)
                    ponerTexto("occupation", ocupacion)
                    ponerTexto("favorite_music", musica)
                }

                // UPDATE con filtro por id: la fila ya existe desde el Paso 1
                SupabaseClient.client.postgrest["profiles"].update(fila) {
                    filter { eq("id", userId) }
                }

                // Reemplazo de los intereses: DELETE de los anteriores + INSERT
                SupabaseClient.client.postgrest["user_interests"].delete {
                    filter { eq("user_id", userId) }
                }

                val filas = intereses.map { interesId ->
                    buildJsonObject {
                        put("user_id", userId)
                        put("interest_id", interesId)
                    }
                }
                if (filas.isNotEmpty()) {
                    SupabaseClient.client.postgrest["user_interests"].insert<JsonObject>(filas)
                }

                if (!activa) return@launch
                AreloveDialog.show(
                    this@EditProfileActivity,
                    AreloveDialog.Tipo.SUCCESS,
                    getString(R.string.dialog_success_title),
                    getString(R.string.edit_profile_saved_msg),
                    positiveText = getString(R.string.dialog_accept),
                    onPositive = { finish() }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                LogHelper.e(TAG_LOG, "Error guardando el perfil")
                if (activa) {
                    mostrarDialogo(
                        AreloveDialog.Tipo.ERROR,
                        getString(
                            R.string.profile_error_save_detail,
                            getString(R.string.profile_error_save),
                            ErrorSanitizer.limpiar(e.message ?: e.toString())
                        )
                    )
                }
            } finally {
                guardando = false
                if (activa) binding.btnSaveProfile.isEnabled = true
            }
        }
    }

    // ---------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------

    /** Escribe un texto, o JsonNull si está vacío (para poder borrar el valor). */
    private fun JsonObjectBuilder.ponerTexto(clave: String, valor: String) {
        if (valor.isEmpty()) put(clave, JsonNull) else put(clave, valor)
    }

    /** Escribe un número, o JsonNull si no hay valor. */
    private fun JsonObjectBuilder.ponerNumero(clave: String, valor: Int?) {
        if (valor == null) put(clave, JsonNull) else put(clave, valor)
    }

    /** Valor de texto de una columna; ausente, JSON null o vacío => null. */
    private fun textoDe(fila: JsonObject, clave: String): String? {
        val elemento = fila[clave] as? JsonPrimitive ?: return null
        if (elemento is JsonNull) return null
        return elemento.content.trim().ifEmpty { null }
    }

    /** Valor numérico de una columna; ausente, JSON null o no numérico => null. */
    private fun numeroDe(fila: JsonObject, clave: String): Double? {
        val elemento = fila[clave] as? JsonPrimitive ?: return null
        if (elemento is JsonNull) return null
        return elemento.content.toDoubleOrNull()
    }

    private fun mostrarDialogo(
        tipo: AreloveDialog.Tipo,
        mensaje: String,
        alAceptar: (() -> Unit)? = null
    ) {
        if (!activa) return
        val titulo = when (tipo) {
            AreloveDialog.Tipo.ERROR -> getString(R.string.dialog_error_title)
            AreloveDialog.Tipo.WARNING -> getString(R.string.dialog_warning_title)
            AreloveDialog.Tipo.SUCCESS -> getString(R.string.dialog_success_title)
            else -> getString(R.string.dialog_info_title)
        }
        AreloveDialog.show(
            this,
            tipo,
            titulo,
            mensaje,
            onPositive = alAceptar
        )
    }

    /** true mientras la Activity pueda usar sus vistas (nada tras destruirla). */
    private val activa: Boolean
        get() = !isFinishing && !isDestroyed

    private companion object {
        /** Etiqueta para el log de errores. */
        const val TAG_LOG = "EditProfileActivity"

        const val EDAD_MINIMA = 18
        const val EDAD_MAXIMA = 100
        const val EDAD_SUGERIDA = 25
        const val TAG_SELECTOR_FECHA = "selector_fecha_edicion"

        /** Mínimo de intereses marcados (igual que el Paso 2). */
        const val MINIMO_INTERESES = 3

        /** Rangos de los campos opcionales (igual que el Paso 4). */
        const val ALTURA_MINIMA = 100
        const val ALTURA_MAXIMA = 250
        const val PESO_MINIMO = 30
        const val PESO_MAXIMO = 300

        /** Formato visible del campo de fecha de nacimiento (siempre dd/MM/yyyy). */
        val FORMATO_FECHA: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    }
}
