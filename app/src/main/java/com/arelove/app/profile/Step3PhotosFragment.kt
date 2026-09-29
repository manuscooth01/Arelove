package com.arelove.app.profile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arelove.app.ErrorSanitizer
import com.arelove.app.PhotoUploadHelper
import com.arelove.app.R
import com.arelove.app.SupabaseClient
import com.arelove.app.databinding.BottomSheetPhotoSourceBinding
import com.arelove.app.databinding.FragmentStep3PhotosBinding
import com.arelove.app.databinding.ItemPhotoBinding
import com.arelove.app.ui.AreloveDialog
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Paso 3 del asistente de perfil: fotos.
 *
 * Rejilla de 2 columnas con las fotos ya subidas (tabla `media`, type = "profile")
 * y una celda "+" hasta llegar a 6. Al elegir una foto se sube al bucket
 * "avatars" y se guarda su fila al terminar, de modo que si el usuario abandona
 * el paso la foto sigue siendo suya. No se puede avanzar sin 2 fotos subidas.
 */
class Step3PhotosFragment : Fragment() {

    private var _binding: FragmentStep3PhotosBinding? = null
    private val binding get() = _binding!!

    /**
     * Una casilla de la rejilla: [FotoItem.url] si ya está en Supabase,
     * [FotoItem.uriLocal] mientras la subida sigue en curso.
     */
    private data class FotoItem(val url: String?, val uriLocal: Uri?)

    /** Fotos mostradas en la rejilla (en el mismo orden que la tabla). */
    private val fotos = mutableListOf<FotoItem>()

    /** Subidas en curso; `validarYGuardar` las espera antes de contar. */
    private val subidasPendientes = mutableListOf<Job>()

    /** Uri temporal que se le pasa a la app de cámara. */
    private var uriCamara: Uri? = null

    /** Evita recargar la lista mientras ya se está cargando. */
    private var cargando = false

    /** Evita avanzar dos veces si pulsan "Siguiente" repetidamente. */
    private var guardando = false

    // ---------------------------------------------------------------------
    // Launchers (se registran en el constructor: nunca dentro de onViewCreated)
    // ---------------------------------------------------------------------

    private val launcherCamara =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { correcto ->
            if (correcto) {
                uriCamara?.let { procesarFoto(it) }
            }
        }

    private val launcherGaleria =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                procesarFoto(uri)
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
        _binding = FragmentStep3PhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configurarRejilla()
        actualizarContador()
        cargarFotos()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------------
    // Rejilla y contador
    // ---------------------------------------------------------------------

    /** Rejilla de 2 columnas con 4dp de separación entre celdas. */
    private fun configurarRejilla() {
        val b = _binding ?: return
        // La rejilla mide todas sus celdas: no rueda ella sola dentro del ScrollView
        b.rvPhotos.isNestedScrollingEnabled = false
        b.rvPhotos.layoutManager = GridLayoutManager(requireContext(), COLUMNAS)
        b.rvPhotos.adapter = FotosAdapter()
        b.rvPhotos.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val espacio = (ESPACIO_DP * resources.displayMetrics.density).toInt()
                outRect.set(espacio, espacio, espacio, espacio)
            }
        })
    }

    /** Escribe el contador "N de 2 mínimo" / "N fotos" y su color. */
    private fun actualizarContador() {
        val b = _binding ?: return
        val total = fotos.size

        if (subidasPendientes.any { it.isActive }) {
            b.tvPhotoCount.text = getString(R.string.photo_uploading)
            b.progressBarPhotos.visibility = View.VISIBLE
            return
        }

        b.progressBarPhotos.visibility = View.GONE
        b.tvPhotoCount.text = if (total < MIN_FOTOS) {
            getString(R.string.profile_step3_counter, total)
        } else {
            getString(R.string.profile_step3_counter_ok, total)
        }
        b.tvPhotoCount.setTextColor(
            ContextCompat.getColor(
                b.root.context,
                if (total >= MIN_FOTOS) R.color.online_green else R.color.on_surface_variant
            )
        )
    }

    // ---------------------------------------------------------------------
    // Carga de las fotos guardadas
    // ---------------------------------------------------------------------

    /** SELECT * FROM media WHERE user_id = auth.uid() AND type = 'profile'. */
    private fun cargarFotos() {
        val b = _binding ?: return
        if (cargando) return
        cargando = true
        b.progressBarPhotos.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
                if (userId == null) {
                    mostrarErrorLoad(getString(R.string.profile_error_no_session))
                    return@launch
                }

                val filas = SupabaseClient.client.postgrest["media"].select {
                    filter {
                        eq("user_id", userId)
                        eq("type", "profile")
                    }
                    order("created_at", Order.ASCENDING)
                }.decodeList<JsonObject>()

                val urls = filas.mapNotNull { (it["url"] as? JsonPrimitive)?.content }

                val sinVista = _binding ?: return@launch
                sinVista.progressBarPhotos.visibility = View.GONE
                fotos.clear()
                fotos.addAll(urls.map { FotoItem(url = it, uriLocal = null) })
                sinVista.rvPhotos.adapter?.notifyDataSetChanged()
                actualizarContador()
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error cargando las fotos", e)
                mostrarErrorLoad(
                    getString(
                        R.string.profile_error_save_detail,
                        getString(R.string.error_generic),
                        ErrorSanitizer.limpiar(e.message ?: e.toString())
                    )
                )
            } finally {
                cargando = false
            }
        }
    }

    /** Deja la rejilla cargada y enseña el diálogo de error (con reintento). */
    private fun mostrarErrorLoad(mensaje: String) {
        val contexto = context ?: return
        _binding?.progressBarPhotos?.visibility = View.GONE
        AreloveDialog.show(
            contexto,
            AreloveDialog.Tipo.ERROR,
            getString(R.string.dialog_error_title),
            mensaje,
            // Al aceptar volvemos a intentar la carga
            onPositive = { cargarFotos() }
        )
    }

    // ---------------------------------------------------------------------
    // Selección y subida de fotos
    // ---------------------------------------------------------------------

    /** Abre la hoja inferior con las dos opciones (cámara o galería). */
    private fun mostrarSelectorDeOrigen() {
        val contexto = context ?: return
        if (cargando || fotos.size >= MAX_FOTOS) return

        val hoja = BottomSheetDialog(contexto)
        val hojaBinding = BottomSheetPhotoSourceBinding.inflate(LayoutInflater.from(contexto))
        hoja.setContentView(hojaBinding.root)

        hojaBinding.rowTakePhoto.setOnClickListener {
            hoja.dismiss()
            pedirPermisoCamara()
        }
        hojaBinding.rowPickPhoto.setOnClickListener {
            hoja.dismiss()
            abrirGaleria()
        }
        hoja.show()
    }

    /** Selector de imágenes del sistema (sin permisos: lo gestiona el picker). */
    private fun abrirGaleria() {
        if (fotos.size >= MAX_FOTOS) return
        launcherGaleria.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }

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
        if (fotos.size >= MAX_FOTOS) return
        val contexto = context ?: return

        try {
            val archivo = File(contexto.cacheDir, "arelove_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                contexto,
                "${contexto.packageName}.fileprovider",
                archivo
            )
            uriCamara = uri
            launcherCamara.launch(uri)
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error preparando la foto de cámara", e)
            AreloveDialog.show(
                contexto,
                AreloveDialog.Tipo.ERROR,
                getString(R.string.dialog_error_title),
                getString(
                    R.string.profile_error_save_detail,
                    getString(R.string.error_generic),
                    ErrorSanitizer.limpiar(e.message ?: e.toString())
                )
            )
        }
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

    /**
     * Reserva la casilla en la rejilla y lanza la subida de [uri].
     * La fila de `media` se guarda al terminar la subida.
     */
    private fun procesarFoto(uri: Uri) {
        val b = _binding ?: return
        if (cargando || fotos.size >= MAX_FOTOS) return

        val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
        if (userId == null) {
            AreloveDialog.show(
                b.root.context,
                AreloveDialog.Tipo.ERROR,
                getString(R.string.dialog_error_title),
                getString(R.string.profile_error_no_session)
            )
            return
        }

        fotos.add(FotoItem(url = null, uriLocal = uri))
        b.rvPhotos.adapter?.notifyDataSetChanged()

        subidasPendientes += lifecycleScope.launch {
            val url = subirYGuardar(userId, uri)
            val sinVista = _binding ?: return@launch

            val posicion = fotos.indexOfFirst { it.uriLocal == uri }
            if (url != null && posicion >= 0) {
                fotos[posicion] = FotoItem(url = url, uriLocal = null)
            } else {
                if (posicion >= 0) fotos.removeAt(posicion)
                sinVista.tvPhotosError.text = getString(R.string.error_upload_photo)
                sinVista.tvPhotosError.visibility = View.VISIBLE
            }

            sinVista.rvPhotos.adapter?.notifyDataSetChanged()
            actualizarContador()
        }

        // Ya hay una subida en curso: el contador pasa a "Subiendo…"
        actualizarContador()
    }

    /** Sube la foto al bucket y guarda su fila en `media`. */
    private suspend fun subirYGuardar(userId: String, uri: Uri): String? {
        val contexto = context ?: return null
        val bytes = PhotoUploadHelper.recomprimir(contexto, uri) ?: return null
        val url = PhotoUploadHelper.subirFoto(userId, bytes) ?: return null

        return try {
            SupabaseClient.client.postgrest["media"].insert<JsonObject>(buildJsonObject {
                put("user_id", userId)
                put("url", url)
                put("type", "profile")
            })
            url
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error guardando la fila de la foto", e)
            // Si la fila no entra, el archivo no debe quedarse huérfano
            PhotoUploadHelper.borrarFoto(url)
            null
        }
    }

    // ---------------------------------------------------------------------
    // Borrado de una foto
    // ---------------------------------------------------------------------

    /** DELETE de la fila de `media` y del archivo del bucket. */
    private fun quitarFoto(posicion: Int) {
        val foto = fotos.getOrNull(posicion) ?: return
        // Mientras sube no se puede borrar (todavía no tiene fila)
        val url = foto.url ?: return
        val b = _binding ?: return
        b.progressBarPhotos.visibility = View.VISIBLE

        lifecycleScope.launch {
            var mensajeError: String? = null
            var filaBorrada = false

            try {
                SupabaseClient.client.postgrest["media"].delete {
                    filter { eq("url", url) }
                }
                filaBorrada = true

                if (!PhotoUploadHelper.borrarFoto(url)) {
                    mensajeError = getString(R.string.error_delete_photo)
                }
            } catch (e: Exception) {
                Log.e(TAG_LOG, "Error borrando la foto", e)
                mensajeError = getString(
                    R.string.profile_error_save_detail,
                    getString(R.string.error_delete_photo),
                    ErrorSanitizer.limpiar(e.message ?: e.toString())
                )
            }

            if (filaBorrada) {
                val indice = fotos.indexOfFirst { it.url == url }
                if (indice >= 0) {
                    fotos.removeAt(indice)
                    _binding?.rvPhotos?.adapter?.notifyDataSetChanged()
                }
                actualizarContador()
            }

            _binding?.progressBarPhotos?.visibility = View.GONE

            val error = mensajeError
            if (error != null) {
                context?.let {
                    AreloveDialog.show(
                        it,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.dialog_error_title),
                        error
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Validación para avanzar
    // ---------------------------------------------------------------------

    /**
     * Espera a que terminen las subidas en curso y comprueba que haya al
     * menos [MIN_FOTOS] fotos subidas.
     *
     * @param onSuccess se llama cuando el paso está completo (para avanzar)
     * @param onError se llama con el mensaje de error a mostrar
     */
    fun validarYGuardar(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val b = _binding ?: return
        b.tvPhotosError.visibility = View.GONE

        val userId = SupabaseClient.client.auth.currentSessionOrNull()?.user?.id
        if (userId == null) {
            onError(getString(R.string.profile_error_no_session))
            return
        }

        if (guardando) return
        guardando = true

        lifecycleScope.launch {
            try {
                if (subidasPendientes.any { it.isActive }) {
                    b.progressBarPhotos.visibility = View.VISIBLE
                    b.tvPhotoCount.text = getString(R.string.photo_uploading)
                    subidasPendientes.forEach { it.join() }
                    b.progressBarPhotos.visibility = View.GONE
                    actualizarContador()
                }

                val subidas = fotos.count { it.url != null }
                if (subidas < MIN_FOTOS) {
                    b.tvPhotosError.text = getString(R.string.profile_step3_error_min)
                    b.tvPhotosError.visibility = View.VISIBLE
                    return@launch
                }

                onSuccess()
            } finally {
                guardando = false
            }
        }
    }

    // ---------------------------------------------------------------------
    // Adaptador
    // ---------------------------------------------------------------------

    /** Adaptador con dos tipos de celda: foto subida y celda "+". */
    private inner class FotosAdapter : RecyclerView.Adapter<FotosAdapter.Celda>() {

        inner class Celda(val vista: ItemPhotoBinding) : RecyclerView.ViewHolder(vista.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Celda =
            Celda(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int =
            fotos.size + (if (fotos.size < MAX_FOTOS) 1 else 0)

        override fun getItemViewType(position: Int): Int =
            if (position < fotos.size) TIPO_FOTO else TIPO_ANADIR

        override fun onBindViewHolder(holder: Celda, position: Int) {
            val b = holder.vista
            val esFoto = position < fotos.size

            b.cardPhoto.visibility = if (esFoto) View.VISIBLE else View.GONE
            b.photoAdd.visibility = if (esFoto) View.GONE else View.VISIBLE

            if (esFoto) {
                val foto = fotos[position]
                val url = foto.url

                when {
                    url != null -> b.ivPhoto.load(url)
                    foto.uriLocal != null -> b.ivPhoto.load(foto.uriLocal)
                }

                // El aspa solo aparece cuando la foto ya está subida
                b.btnPhotoRemove.visibility = if (url != null) View.VISIBLE else View.GONE
                b.btnPhotoRemove.setOnClickListener {
                    // adapterPosition (no bindingAdapterPosition): la versión
                    // resuelta de recyclerview es 1.0.0 y aún no tiene esa API
                    val indice = holder.adapterPosition
                    if (indice != RecyclerView.NO_POSITION) quitarFoto(indice)
                }
            } else {
                b.photoAdd.setOnClickListener { mostrarSelectorDeOrigen() }
            }

            fijarProporcion(holder.itemView)
        }
    }

    /**
     * Fija la celda a proporción 3:4 según el ancho de la columna.
     * No hay ConstraintLayout en el proyecto, así que el ratio no se puede
     * declarar en XML y se calcula cuando la celda ya tiene ancho.
     */
    private fun fijarProporcion(vista: View) {
        if (vista.layoutParams.height > 0) return
        vista.post {
            val ancho = vista.width
            if (ancho <= 0) return@post
            val parametros = vista.layoutParams
            parametros.height = ancho * ALTO_IMAGEN / ANCHO_IMAGEN
            vista.layoutParams = parametros
        }
    }

    private companion object {
        /** Etiqueta para el log de errores de este paso. */
        const val TAG_LOG = "Step3Photos"

        /** Fotos mínimas para avanzar y máximo que se pueden subir. */
        const val MIN_FOTOS = 2
        const val MAX_FOTOS = 6

        /** Columnas de la rejilla y proporción de cada celda (3:4). */
        const val COLUMNAS = 2
        const val ANCHO_IMAGEN = 3
        const val ALTO_IMAGEN = 4

        /** Separación entre celdas, en dp. */
        const val ESPACIO_DP = 4

        /** Tipos de vista del adaptador. */
        const val TIPO_FOTO = 0
        const val TIPO_ANADIR = 1
    }
}
