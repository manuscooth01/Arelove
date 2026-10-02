package com.arelove.app.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.ErrorSanitizer
import com.arelove.app.LocationHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentStep1BasicBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Paso 1 del asistente de perfil: apodo, fecha de nacimiento, lugar y qué buscas.
 *
 * Guarda los datos en la tabla `profiles` de Supabase con un UPSERT sobre `auth.uid()`.
 * Los valores de "looking_for" que se guardan son: "pareja", "amistades" o "ambos".
 * La fecha de nacimiento se guarda en formato ISO (YYYY-MM-DD) y la edad se
 * calcula a partir de ella; las coordenadas del GPS se guardan solo si el
 * usuario pulsó "Usar mi ubicación".
 */
class Step1BasicFragment : Fragment() {

    private var _binding: FragmentStep1BasicBinding? = null
    private val binding get() = _binding!!

    /** Evita guardar dos veces si pulsan "Siguiente" repetidamente. */
    private var guardando = false

    /** Fecha elegida, para reabrir el calendario en ese día. */
    private var fechaNacimiento: LocalDate? = null

    /** Coordenadas del GPS; null si el lugar se escribió a mano. */
    private var coordenadas: LocationHelper.Coordenadas? = null

    /** Evita borrar las coordenadas cuando rellenamos el campo desde el GPS. */
    private var rellenandoLugar = false

    /**
     * Selector de fecha construido de forma perezosa en el primer toque sobre
     * el campo (no se monta en onViewCreated) y cacheado para reutilizarlo.
     */
    private var datePicker: MaterialDatePicker<Long>? = null

    /** Marca si el selector actual ya se mostró (un fragmento no debe reutilizarse tras descartarse). */
    private var selectorUsado = false

    /** Pide los permisos de ubicación al pulsar "Usar mi ubicación". */
    private val solicitudPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        if (resultados.values.any { it }) {
            obtenerUbicacion()
        } else {
            avisarPermisoDenegado()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep1BasicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configurarCiudades()
        prellenarNickname()
        configurarChips()
        configurarFechaNacimiento()
        configurarLugar()
        cargarDatosExistentes()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Sugerencias de ciudades más comunes de Venezuela (con el item de Material). */
    private fun configurarCiudades() {
        binding.etLocation.setSimpleItems(resources.getStringArray(R.array.venezuela_cities))
    }

    /** Pre-llena el apodo con el user_metadata.nickname del usuario. */
    private fun prellenarNickname() {
        val metadata = SupabaseClient.client.auth.currentUserOrNull()?.userMetadata ?: return
        val nickname = (metadata["nickname"] as? JsonPrimitive)?.content?.trim()

        if (!nickname.isNullOrEmpty() && binding.etNickname.text.isNullOrBlank()) {
            binding.etNickname.setText(nickname)
        }
    }

    /** Quita el error de "Busco" en cuanto eligen una opción. */
    private fun configurarChips() {
        binding.chipGroupLooking.setOnCheckedStateChangeListener { _, idsSeleccionados ->
            if (idsSeleccionados.isNotEmpty()) {
                binding.tvLookingError.visibility = View.GONE
            }
        }

        binding.chipGroupGender.setOnCheckedStateChangeListener { _, idsSeleccionados ->
            if (idsSeleccionados.isNotEmpty()) {
                binding.tvGenderError.visibility = View.GONE
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
            val b = _binding ?: return@doAfterTextChanged
            // Si el usuario edita el texto a mano, descartamos las coordenadas del GPS
            if (!rellenandoLugar) coordenadas = null
            b.tilLocation.error = null
        }

        binding.btnUseLocation.setOnClickListener {
            if (AntiSpam.esDobleTap("btnUseLocation")) return@setOnClickListener
            pedirUbicacion()
        }
    }

    // ---------------------------------------------------------------------
    // Selector de fecha de nacimiento
    // ---------------------------------------------------------------------

    /**
     * Muestra un [MaterialDatePicker] limitado a fechas pasadas:
     * entre hoy - 100 años y hoy, abriendo en hoy - 25 años.
     *
     * El selector se construye de forma perezosa en este primer toque (no en
     * onViewCreated) y se cachea en [datePicker]. MaterialDatePicker no permite
     * cambiar la selección una vez construido y un fragmento no debe
     * reutilizarse tras su descarte, así que si el selector ya se mostró
     * (o cambió la fecha de apertura) se reconstruye con la fecha actual.
     */
    private fun mostrarSelectorFecha() {
        if (_binding == null) return

        val selectorPreparado = datePicker
        if (selectorPreparado == null || selectorUsado || selectorPreparado.selection != millisUtc(fechaInicial())) {
            datePicker = construirSelector()
        }

        selectorUsado = true
        datePicker?.show(parentFragmentManager, TAG_SELECTOR_FECHA)
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
        val b = _binding ?: return
        val edad = Period.between(fecha, LocalDate.now()).years

        fechaNacimiento = fecha
        b.etBirthdate.setText(FORMATO_FECHA.format(fecha))
        b.tilBirthdate.error = when {
            edad < EDAD_MINIMA -> getString(R.string.validation_min_age)
            edad > EDAD_MAXIMA -> getString(R.string.validation_invalid_birthdate)
            else -> null
        }
    }

    /** Convierte "dd/MM/yyyy" a [LocalDate], o null si el texto no es válido. */
    private fun parsearFecha(texto: String): LocalDate? = try {
        LocalDate.parse(texto, FORMATO_FECHA)
    } catch (e: DateTimeParseException) {
        null
    }

    /** millis en UTC a medianoche, como espera MaterialDatePicker. */
    private fun millisUtc(fecha: LocalDate): Long =
        fecha.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    // ---------------------------------------------------------------------
    // Ubicación (GPS)
    // ---------------------------------------------------------------------

    /** Pide permisos si hacen falta y, si ya los hay, obtiene la ubicación. */
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
        val contexto = context ?: return false

        val fino = ContextCompat.checkSelfPermission(
            contexto, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val grueso = ContextCompat.checkSelfPermission(
            contexto, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fino || grueso
    }

    /** Rellena el campo de lugar con la ciudad detectada por el GPS. */
    private fun obtenerUbicacion() {
        val contexto = context ?: return

        lifecycleScope.launch {
            try {
                val ubicacion = LocationHelper.obtenerUbicacionActual(contexto)
                val ciudad = ubicacion.ciudad

                if (ciudad != null) {
                    coordenadas = LocationHelper.Coordenadas(
                        latitude = ubicacion.latitude,
                        longitude = ubicacion.longitude
                    )
                }

                // Si el fragmento perdió su vista mientras esperábamos al GPS, no
                // tocamos widgets: solo se descartan los datos del GPS.
                val b = _binding ?: return@launch

                if (ciudad == null) {
                    mostrarDialogo(
                        AreloveDialog.Tipo.ERROR,
                        contexto.getString(R.string.location_error)
                    )
                    return@launch
                }

                rellenandoLugar = true
                b.etLocation.setText(ciudad)
                rellenandoLugar = false
            } catch (e: SecurityException) {
                if (_binding == null) return@launch
                mostrarDialogo(
                    AreloveDialog.Tipo.ERROR,
                    contexto.getString(R.string.location_permission_denied)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                mostrarDialogo(
                    AreloveDialog.Tipo.ERROR,
                    ErrorSanitizer.limpiar(e.message ?: contexto.getString(R.string.location_error))
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
        val contexto = context ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", contexto.packageName, null)
        }
        startActivity(intent)
    }

    private fun mostrarDialogo(
        tipo: AreloveDialog.Tipo,
        mensaje: String,
        alAceptar: (() -> Unit)? = null
    ) {
        val contexto = context ?: return
        val titulo = when (tipo) {
            AreloveDialog.Tipo.ERROR -> getString(R.string.dialog_error_title)
            AreloveDialog.Tipo.WARNING -> getString(R.string.dialog_warning_title)
            else -> getString(R.string.dialog_info_title)
        }
        AreloveDialog.show(
            contexto,
            tipo,
            titulo,
            mensaje,
            onPositive = alAceptar
        )
    }

    // ---------------------------------------------------------------------
    // Carga de los datos ya guardados
    // ---------------------------------------------------------------------

    /**
     * SELECT nickname, birthdate, location, looking_for, latitude, longitude
     *     FROM profiles WHERE id = userId LIMIT 1
     *
     * Rellena el paso al retomar el asistente (por ejemplo al volver desde el
     * Paso 3), igual que hace el Paso 4. Si la carga falla no se muestra
     * diálogo: el usuario puede rellenar los campos a mano.
     */
    private fun cargarDatosExistentes() {
        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina (nunca en el hilo principal)
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                    ?: return@launch

                val fila = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list(
                        "nickname", "birthdate", "location", "looking_for",
                        "gender", "latitude", "longitude"
                    )
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeSingleOrNull<JsonObject>() ?: return@launch

                val b = _binding ?: return@launch

                // Apodo: si está vacío se queda el prellenado del user_metadata
                textoDe(fila, "nickname")?.let { b.etNickname.setText(it) }

                // Fecha de nacimiento: ISO yyyy-MM-dd en BD → dd/MM/yyyy en pantalla
                textoDe(fila, "birthdate")?.let { iso ->
                    try {
                        // Escribe el campo, fija fechaNacimiento y valida la edad
                        aplicarFechaNacimiento(LocalDate.parse(iso))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Cancelacion normal (navegacion del usuario): se relanza
                        // y nunca se traga (regla de corrutinas de Kotlin).
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG_LOG, "Error parseando birthdate: $iso", e)
                    }
                }

                // Lugar: sin disparar el filtro del desplegable y sin borrar las
                // coordenadas (misma bandera que usa el relleno por GPS)
                textoDe(fila, "location")?.let { lugar ->
                    rellenandoLugar = true
                    b.etLocation.setText(lugar, false)
                    rellenandoLugar = false
                }

                // Coordenadas guardadas: se restauran para no perderlas si luego
                // se vuelve a guardar con el lugar escrito a mano
                val lat = numeroDe(fila, "latitude")
                val lng = numeroDe(fila, "longitude")
                if (lat != null && lng != null) {
                    coordenadas = LocationHelper.Coordenadas(latitude = lat, longitude = lng)
                }

                // "Busco": el ChipGroup es singleSelection, así que marcar uno
                // desmarca los demás y dispara el listener que oculta el error
                when (textoDe(fila, "looking_for")) {
                    "pareja" -> b.chipPareja.isChecked = true
                    "amistades" -> b.chipAmistades.isChecked = true
                    "ambos" -> b.chipAmbos.isChecked = true
                }

                // Género
                when (textoDe(fila, "gender")) {
                    "hombre" -> b.chipHombre.isChecked = true
                    "mujer" -> b.chipMujer.isChecked = true
                    "otro" -> b.chipOtro.isChecked = true
                    "prefiero_no_decir" -> b.chipPrefieroNoDecir.isChecked = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error cargando el Paso 1", e)
            }
        }
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

    // ---------------------------------------------------------------------
    // Validación y guardado
    // ---------------------------------------------------------------------

    /** Opción seleccionada en los chips, o null si no hay ninguna. */
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
     * Valida los campos del paso 1 y, si todo está correcto, hace un UPSERT
     * en la tabla `profiles` con id = auth.uid().
     *
     * @param onSuccess se llama cuando el guardado termina bien (para avanzar de paso)
     * @param onError se llama con el mensaje de error a mostrar
     */
    fun validarYGuardar(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val b = _binding ?: return

        // Limpiamos los errores de la validación anterior
        b.tilNickname.error = null
        b.tilBirthdate.error = null
        b.tilLocation.error = null
        b.tvLookingError.visibility = View.GONE

        val nickname = b.etNickname.text?.toString()?.trim().orEmpty()
        val fechaTexto = b.etBirthdate.text?.toString()?.trim().orEmpty()
        val location = b.etLocation.text?.toString()?.trim().orEmpty()
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

        if (guardando) return
        guardando = true

        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina: en el hilo principal
                // currentSessionOrNull() puede bloquear y congelar la pantalla
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                if (userId == null) {
                    onError(getString(R.string.profile_error_no_session))
                    return@launch
                }

                val coords = coordenadas
                val fila = buildJsonObject {
                    put("id", userId)
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
                }
                // UPSERT: crea la fila si no existe y la actualiza si ya existe
                SupabaseClient.client.postgrest["profiles"].upsert(fila)
                onSuccess()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                // Stacktrace completo en logcat para diagnosticar la causa real
                Log.e(TAG_LOG, "Error guardando perfil", e)
                onError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.profile_error_save),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                // Siempre se libera el bloqueo para poder reintentar
                guardando = false
            }
        }
    }

    private companion object {
        /** Etiqueta para el log de errores de guardado. */
        const val TAG_LOG = "Step1Basic"

        const val EDAD_MINIMA = 18
        const val EDAD_MAXIMA = 100
        const val EDAD_SUGERIDA = 25
        const val TAG_SELECTOR_FECHA = "selector_fecha_nacimiento"

        /** Formato visible del campo de fecha de nacimiento (siempre dd/MM/yyyy). */
        val FORMATO_FECHA: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    }
}
