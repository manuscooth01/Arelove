package com.arelove.app.profile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arelove.app.ErrorSanitizer
import com.arelove.app.PhotoUploadHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.FragmentStep5VerificationBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Paso 5 (último) del asistente de perfil: selfie de verificación.
 *
 * La foto se sube al bucket "verification" de Supabase Storage y solo se
 * guarda su URL en `profiles.verification_photo_url`; la columna `verified`
 * la pone el equipo de Arelove tras revisarla, así que aquí no se toca.
 * Se puede saltar el paso con "Saltar por ahora" o avanzar con "Siguiente":
 * ambas rutas llaman al mismo [validarYGuardar], que sin selfie pide
 * confirmación antes de terminar el asistente.
 *
 * También se usa fuera del asistente, alojado por [MyVerificationActivity]
 * con el modo edición ([modoEdicion]): allí se ocultan la cabecera interna
 * (la pone la Activity) y "Saltar por ahora" (solo sirve para avanzar el
 * asistente). La subida y el estado son idénticos y la pantalla se cierra
 * con la X, nunca sola.
 */
class Step5VerificationFragment : Fragment() {

    private var _binding: FragmentStep5VerificationBinding? = null
    private val binding get() = _binding!!

    /** URL de la selfie subida (en esta sesión o recuperada al recrear la vista). */
    private var urlSelfie: String? = null

    /** Uri temporal que se le pasa a la app de cámara. */
    private var uriCamara: Uri? = null

    /** Evita lanzar dos subidas a la vez. */
    private var subiendo = false

    /** Evita avanzar dos veces si pulsan "Siguiente" repetidamente. */
    private var guardando = false

    /** Trabajo en curso de la preview; se cancela al pedir una nueva. */
    private var jobPreview: Job? = null

    // ---------------------------------------------------------------------
    // Launchers (se registran en el constructor: nunca dentro de onViewCreated)
    // ---------------------------------------------------------------------

    private val launcherCamara =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { correcto ->
            if (correcto) {
                uriCamara?.let { procesarSelfie(it) }
            }
        }

    private val launcherGaleria =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                procesarSelfie(uri)
            }
        }

    private val launcherPermisoCamara =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                abrirCamara()
            } else {
                mostrarPermisoDenegado()
            }
        }

    // ---------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep5VerificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val modoEdicion = arguments?.getBoolean(ARG_MODO_EDICION, false) == true
        if (modoEdicion) {
            // La cabecera la pinta MyVerificationActivity
            binding.tvTitle.visibility = View.GONE
            binding.tvSubtitle.visibility = View.GONE

            // "Saltar por ahora" solo avanza el asistente: emite CLAVE_AVANZAR
            // a ProfileSetupActivity, y aquí nadie la escucha (botón muerto).
            // Si ya hay selfie, mostrarSelfieSubida() lo oculta igualmente.
            binding.btnSkip.visibility = View.GONE
        }

        binding.btnTakeSelfie.setOnClickListener {
            if (AntiSpam.esDobleTap("btnTakeSelfie")) return@setOnClickListener
            pedirPermisoCamara()
        }
        binding.btnPickSelfie.setOnClickListener {
            if (AntiSpam.esDobleTap("btnPickSelfie")) return@setOnClickListener
            abrirGaleria()
        }
        binding.btnSkip.setOnClickListener {
            if (AntiSpam.esDobleTap("btnSkip")) return@setOnClickListener
            saltarVerificacion()
        }

        // Restaura la selfie de esta sesión o la que ya esté en Supabase
        cargarSelfieGuardada()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------------
    // Restauración de la selfie guardada
    // ---------------------------------------------------------------------

    /**
     * Restaura la selfie pendiente al abrir el paso: usa la de esta sesión
     * si la hay ([urlSelfie]) y, si no, consulta
     * `profiles.verification_photo_url` (en una sesión anterior la URL solo
     * vivía en memoria y la preview no se recuperaba nunca).
     */
    private fun cargarSelfieGuardada() {
        val urlEnSesion = urlSelfie
        if (urlEnSesion != null) {
            mostrarSelfieSubida(urlEnSesion)
            return
        }

        lifecycleScope.launch {
            try {
                // La sesión se lee dentro de la corrutina (nunca en el hilo principal)
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                    ?: return@launch

                val fila = SupabaseClient.client.postgrest["profiles"].select(
                    columns = Columns.list("verification_photo_url")
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }.decodeSingleOrNull<JsonObject>() ?: return@launch

                val url = textoDe(fila, "verification_photo_url") ?: return@launch
                urlSelfie = url

                // Sin vista (paso destruido) no se pinta nada: en el próximo
                // onViewCreated urlSelfie ya no será null y se restaura solo
                mostrarSelfieSubida(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                // Sin conexión o sin permiso: el paso sigue funcionando sin preview
                Log.e(TAG_LOG, "Error cargando la selfie guardada", e)
            }
        }
    }

    /** Valor de texto de una columna; ausente, JSON null o vacío => null. */
    private fun textoDe(fila: JsonObject, clave: String): String? {
        val elemento = fila[clave] as? JsonPrimitive ?: return null
        if (elemento is JsonNull) return null
        return elemento.content.trim().ifEmpty { null }
    }

    // ---------------------------------------------------------------------
    // Cámara y galería
    // ---------------------------------------------------------------------

    /** Pide el permiso de cámara solo si aún no fue concedido. */
    private fun pedirPermisoCamara() {
        val contexto = context ?: return
        if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            abrirCamara()
        } else {
            launcherPermisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    /** Crea un archivo temporal en caché y lo comparte con la app de cámara. */
    private fun abrirCamara() {
        val contexto = context ?: return

        try {
            // Subcarpeta propia (VUL-14): el FileProvider solo expone cacheDir/selfie
            val carpetaSelfie = File(contexto.cacheDir, "selfie")
            carpetaSelfie.mkdirs()
            val archivo = File(carpetaSelfie, "arelove_selfie_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                contexto,
                "${contexto.packageName}.fileprovider",
                archivo
            )
            uriCamara = uri
            launcherCamara.launch(uri)
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error preparando la selfie de cámara", e)
            mostrarErrorSubida()
        }
    }

    /** Selector de imágenes del sistema (sin permisos: lo gestiona el picker). */
    private fun abrirGaleria() {
        launcherGaleria.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }

    private fun mostrarPermisoDenegado() {
        val contexto = context ?: return
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.WARNING,
            getString(R.string.dialog_warning_title),
            getString(R.string.error_camera_permission)
        )
    }

    // ---------------------------------------------------------------------
    // Subida de la selfie
    // ---------------------------------------------------------------------

    /**
     * Recomprime la [uri] con PhotoUploadHelper, la sube al bucket
     * "verification" y guarda su URL en `profiles`.
     */
    private fun procesarSelfie(uri: Uri) {
        val b = _binding ?: return
        if (subiendo) return
        subiendo = true

        b.progressBarVerification.visibility = View.VISIBLE
        b.tvVerificationStatus.text = getString(R.string.profile_step5_status_pending)
        b.btnTakeSelfie.isEnabled = false
        b.btnPickSelfie.isEnabled = false

        lifecycleScope.launch {
            var mensajeError: String? = null

            try {
                // La sesión se lee dentro de la corrutina (nunca en el hilo principal)
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                if (userId == null) {
                    mensajeError = getString(R.string.profile_error_no_session)
                } else {
                    val contexto = context
                    val bytes = if (contexto != null) {
                        PhotoUploadHelper.recomprimir(contexto, uri)
                    } else {
                        null
                    }
                    val url = if (bytes != null) {
                        PhotoUploadHelper.subirFoto(userId, bytes, BUCKET_VERIFICACION)
                    } else {
                        null
                    }

                    if (url == null) {
                        mensajeError = getString(R.string.error_upload_verification)
                    } else {
                        // Solo se guarda la URL; "verified" la pone el equipo de Arelove
                        guardarSelfie(userId, url)
                        urlSelfie = url
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error guardando la selfie de verificación", e)
                mensajeError = getString(
                    R.string.profile_error_save_detail,
                    getString(R.string.error_upload_verification),
                    ErrorSanitizer.limpiar(e.message ?: e.toString())
                )
            } finally {
                subiendo = false
            }

            val sinVista = _binding ?: return@launch
            sinVista.progressBarVerification.visibility = View.GONE
            sinVista.btnTakeSelfie.isEnabled = true
            sinVista.btnPickSelfie.isEnabled = true

            val url = urlSelfie
            if (url != null) {
                mostrarSelfieSubida(url)
            } else {
                sinVista.tvVerificationStatus.text =
                    getString(R.string.profile_step5_status_unverified)
                sinVista.tvVerificationStatus.setTextColor(
                    ContextCompat.getColor(sinVista.root.context, R.color.on_surface_variant)
                )
            }

            val error = mensajeError
            if (error != null) {
                AreloveDialog.show(
                    sinVista.root.context,
                    AreloveDialog.Tipo.ERROR,
                    getString(R.string.dialog_error_title),
                    error
                )
            }
        }
    }

    /**
     * Muestra la selfie subida: estado "en revisión" y sin "Saltar".
     *
     * El bucket "verification" es privado, así que la URL guardada no sirve
     * para la vista previa: la preview se carga con una URL firmada.
     */
    private fun mostrarSelfieSubida(url: String) {
        val b = _binding ?: return
        b.tvVerificationStatus.text = getString(R.string.profile_step5_status_review)
        b.tvVerificationStatus.setTextColor(
            ContextCompat.getColor(b.root.context, R.color.dialog_warning)
        )
        b.btnSkip.visibility = View.GONE
        cargarPreview(url)
    }

    /**
     * Pide una URL firmada temporal ([PhotoUploadHelper.urlFirmada]) para la
     * selfie y la carga en la preview. El ImageView se hace visible ANTES de
     * cargar (Coil no arranca si el target mide 0 por estar GONE); si Coil
     * falla se intenta una descarga manual y, si esa también falla, se oculta.
     */
    private fun cargarPreview(url: String) {
        val path = extraerPathVerificacion(url)
        if (path == null) {
            _binding?.ivSelfiePreview?.visibility = View.GONE
            return
        }

        // Solo una preview en vuelo: la anterior se cancela al pedir esta
        jobPreview?.cancel()
        jobPreview = viewLifecycleOwner.lifecycleScope.launch {
            val sinVista = _binding ?: return@launch
            // Visible ANTES de cargar: Coil necesita que el target tenga tamaño
            sinVista.ivSelfiePreview.visibility = View.VISIBLE
            sinVista.ivSelfiePreview.setImageDrawable(null) // limpia

            val firmada = PhotoUploadHelper.urlFirmada(BUCKET_VERIFICACION, path)
            if (firmada == null) {
                sinVista.ivSelfiePreview.visibility = View.GONE
                return@launch
            }

            sinVista.ivSelfiePreview.load(firmada) {
                crossfade(true)
                listener(
                    onError = { _, result ->
                        Log.e(TAG_LOG, "Coil error, intentando descarga manual", result.throwable)
                        // Descarga manual con el cliente HTTP de Supabase
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val bytes = SupabaseClient.client.storage[BUCKET_VERIFICACION]
                                    .downloadAuthenticated(path)
                                val bitmap = android.graphics.BitmapFactory
                                    .decodeByteArray(bytes, 0, bytes.size)
                                _binding?.ivSelfiePreview?.setImageBitmap(bitmap)
                                _binding?.ivSelfiePreview?.visibility = View.VISIBLE
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // Cancelacion normal (navegacion del usuario): se relanza
                                // y nunca se traga (regla de corrutinas de Kotlin).
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG_LOG, "Descarga manual también falló", e)
                                _binding?.ivSelfiePreview?.visibility = View.GONE
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Extrae la ruta interna de la selfie desde su URL pública:
     * `.../object/public/verification/abc/1.jpg` -> `abc/1.jpg`.
     */
    private fun extraerPathVerificacion(url: String): String? {
        val marca = "$BUCKET_VERIFICACION/"
        val indice = url.indexOf(marca)
        if (indice < 0) return null
        return url.substring(indice + marca.length).ifEmpty { null }
    }

    private fun mostrarErrorSubida() {
        val contexto = context ?: return
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            getString(R.string.error_upload_verification)
        )
    }

    /** UPDATE de la URL de la selfie en `profiles` (no toca la columna "verified"). */
    private suspend fun guardarSelfie(userId: String, url: String) {
        SupabaseClient.client.postgrest["profiles"].update(buildJsonObject {
            put("verification_photo_url", url)
        }) {
            filter { eq("id", userId) }
        }
    }

    // ---------------------------------------------------------------------
    // Salto de la verificación
    // ---------------------------------------------------------------------

    /**
     * "Saltar por ahora" hace lo mismo que el botón "Siguiente": pide a
     * ProfileSetupActivity que ejecute el avance de este paso, así que sin
     * selfie se muestra el aviso de no verificar y, al confirmarlo, se
     * termina el asistente. Con selfie subida se guarda y se termina igual.
     */
    private fun saltarVerificacion() {
        parentFragmentManager.setFragmentResult(CLAVE_AVANZAR, Bundle())
    }

    // ---------------------------------------------------------------------
    // Validación para avanzar
    // ---------------------------------------------------------------------

    /**
     * Si hay selfie subida, vuelve a guardar su URL en `profiles` y avanza.
     * Si no la hay, pide confirmación para continuar sin verificar.
     * En ambas rutas se marca `profiles.profile_completed = true` antes de
     * avanzar: es la única forma de terminar el asistente.
     *
     * @param onSuccess se llama cuando el paso queda resuelto (para terminar el asistente)
     * @param onError se llama con el mensaje de error a mostrar
     */
    fun validarYGuardar(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (guardando) return
        if (_binding == null) return

        val url = urlSelfie
        if (url == null) {
            val contexto = context ?: return
            guardando = true
            AreloveDialog.show(
                contexto,
                AreloveDialog.Tipo.WARNING,
                getString(R.string.dialog_warning_title),
                getString(R.string.profile_step5_skip_warning),
                onPositive = {
                    // Marcar profile_completed antes de avanzar
                    lifecycleScope.launch {
                        try {
                            val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                            if (userId != null) {
                                SupabaseClient.client.postgrest["profiles"].update(
                                    buildJsonObject { put("profile_completed", true) }
                                ) {
                                    filter { eq("id", userId) }
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Cancelacion normal (navegacion del usuario): se relanza
                            // y nunca se traga (regla de corrutinas de Kotlin).
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG_LOG, "Error marcando profile_completed", e)
                        } finally {
                            guardando = false
                            onSuccess()
                        }
                    }
                }
            )
            return
        }

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

                // Un solo UPDATE: la URL de la selfie y el fin del asistente
                SupabaseClient.client.postgrest["profiles"].update(buildJsonObject {
                    put("verification_photo_url", url)
                    put("profile_completed", true)
                }) {
                    filter { eq("id", userId) }
                }

                onSuccess()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelacion normal (navegacion del usuario): se relanza
                // y nunca se traga (regla de corrutinas de Kotlin).
                throw e
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error guardando la selfie de verificación", e)
                onError(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_upload_verification),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                guardando = false
            }
        }
    }

    companion object {
        /**
         * Clave del resultado que este paso envía al pulsar "Saltar por ahora";
         * ProfileSetupActivity la escucha y ejecuta el avance normal del paso 5
         * (validarYGuardar con su aviso de no verificar).
         */
        const val CLAVE_AVANZAR = "avanzar_paso5"

        /** Argumento que activa el modo edición ([modoEdicion]). */
        private const val ARG_MODO_EDICION = "modo_edicion"

        /**
         * Instancia para [MyVerificationActivity]: el mismo paso, pero sin
         * cabecera interna ni botón "Saltar" (los gestiona la Activity).
         */
        fun modoEdicion(): Step5VerificationFragment = Step5VerificationFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_MODO_EDICION, true) }
        }

        /** Etiqueta para el log de errores de este paso. */
        private const val TAG_LOG = "Step5Verification"

        /** Bucket de Supabase Storage donde va la selfie de verificación. */
        private const val BUCKET_VERIFICACION = "verification"
    }
}
