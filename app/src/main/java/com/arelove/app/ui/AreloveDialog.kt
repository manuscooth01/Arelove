package com.arelove.app.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import com.arelove.app.R
import com.arelove.app.databinding.DialogAreloveBinding

/**
 * Diálogo personalizado de Arelove con 4 tipos predefinidos.
 * Usa ViewBinding sobre el layout dialog_arelove.xml.
 */
class AreloveDialog(
    context: Context,
    private val tipo: Tipo,
    private val titulo: String,
    private val mensaje: String,
    private val textoPositivo: String,
    private val alConfirmar: (() -> Unit)?
) : Dialog(context) {

    /** Tipos predefinidos de diálogo. */
    enum class Tipo { ERROR, WARNING, SUCCESS, INFO }

    private lateinit var binding: DialogAreloveBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogAreloveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fondo transparente para que se vean las esquinas redondeadas de la tarjeta
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Márgenes laterales de 24dp alineados al diseño
        val margenPx = (24 * resources.displayMetrics.density).toInt()
        window?.setLayout(
            resources.displayMetrics.widthPixels - margenPx * 2,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Solo se cierra con el botón, para no perder el resultado
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        val icono = when (tipo) {
            Tipo.ERROR -> R.drawable.ic_dialog_error
            Tipo.WARNING -> R.drawable.ic_dialog_warning
            Tipo.SUCCESS -> R.drawable.ic_dialog_success
            Tipo.INFO -> R.drawable.ic_dialog_info
        }

        binding.ivIcon.setImageResource(icono)
        binding.tvTitle.text = titulo
        binding.tvMessage.text = mensaje
        binding.btnPositive.text = textoPositivo

        binding.btnPositive.setOnClickListener {
            dismiss()
            alConfirmar?.invoke()
        }
    }

    companion object {
        /**
         * Muestra un diálogo Arelove con un solo botón.
         *
         * @param type tipo predefinido (ERROR, WARNING, SUCCESS, INFO)
         * @param onPositive se ejecuta al pulsar el botón, después de cerrar
         */
        fun show(
            context: Context,
            type: Tipo,
            title: String,
            message: String,
            positiveText: String = "Aceptar",
            onPositive: (() -> Unit)? = null
        ) {
            AreloveDialog(context, type, title, message, positiveText, onPositive).show()
        }
    }
}
