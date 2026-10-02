package com.arelove.app.profile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arelove.app.R
import com.arelove.app.databinding.ActivityMyPhotosBinding
import com.arelove.app.ui.AreloveDialog
import com.arelove.app.util.AntiSpam

/**
 * Pantalla Mis fotos: añade, consulta y borra las fotos del perfil sin pasar
 * por el asistente de 5 pasos.
 *
 * Aloja [Step3PhotosFragment] en modo edición (la rejilla, la subida y el
 * borrado son los mismos que en el Paso 3) y ofrece un botón "Listo" que
 * espera a las subidas que sigan en curso antes de cerrar.
 */
class MyPhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyPhotosBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseMyPhotos.setOnClickListener {
            if (AntiSpam.esDobleTap("btnCloseMyPhotos")) return@setOnClickListener
            finish()
        }

        binding.btnDoneMyPhotos.setOnClickListener {
            if (AntiSpam.esDobleTap("btnDoneMyPhotos")) return@setOnClickListener
            cerrarCuandoEsteListo()
        }

        // En rotación el FragmentManager ya restaura la instancia
        if (supportFragmentManager.findFragmentById(R.id.containerMyPhotos) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.containerMyPhotos, Step3PhotosFragment.modoEdicion())
                .commit()
        }
    }

    /**
     * Cierra la pantalla cuando el fragmento lo permite (subidas pendientes
     * resueltas y mínimo de fotos alcanzado; si falta, el propio fragmento
     * pinta el aviso bajo la rejilla).
     */
    private fun cerrarCuandoEsteListo() {
        val fragmento = supportFragmentManager
            .findFragmentById(R.id.containerMyPhotos) as? Step3PhotosFragment
        if (fragmento == null) {
            finish()
            return
        }

        fragmento.validarYGuardar(
            onSuccess = { if (!isFinishing) finish() },
            onError = { mensaje ->
                if (!isFinishing) {
                    AreloveDialog.show(
                        this,
                        AreloveDialog.Tipo.ERROR,
                        getString(R.string.dialog_error_title),
                        mensaje
                    )
                }
            }
        )
    }
}
