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
import coil.load
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
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
        binding.btnTakeSelfie.setOnClickListener { pedirPermisoCamara() }
        binding.btnPickSelfie.setOnClickListener { abrirGaleria() }
        binding.btnSkip.setOnClickListener { saltarVerificacion() }

        // Si la vista se recreó después de subir la selfie, restauramos el estado
        urlSelfie?.let { mostrarSelfieSubida(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
            val archivo = File(contexto.cacheDir, "arelove_selfie_${System.currentTimeMillis()}.jpg")
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

    /** Muestra la selfie subida: preview, estado "en revisión" y sin "Saltar". */
    private fun mostrarSelfieSubida(url: String) {
        val b = _binding ?: return
        b.ivSelfiePreview.visibility = View.VISIBLE
        b.ivSelfiePreview.load(url) { crossfade(true) }
        b.tvVerificationStatus.text = getString(R.string.profile_step5_status_review)
        b.tvVerificationStatus.setTextColor(
            ContextCompat.getColor(b.root.context, R.color.dialog_warning)
        )
        b.btnSkip.visibility = View.GONE
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

    /** UPSERT de la URL de la selfie en `profiles` (no toca la columna "verified"). */
    private suspend fun guardarSelfie(userId: String, url: String) {
        SupabaseClient.client.postgrest["profiles"].upsert(buildJsonObject {
            put("id", userId)
            put("verification_photo_url", url)
        })
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
                    guardando = false
                    onSuccess()
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

                guardarSelfie(userId, url)
                onSuccess()
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

        /** Etiqueta para el log de errores de este paso. */
        private const val TAG_LOG = "Step5Verification"

        /** Bucket de Supabase Storage donde va la selfie de verificación. */
        private const val BUCKET_VERIFICACION = "verification"
    }
}
