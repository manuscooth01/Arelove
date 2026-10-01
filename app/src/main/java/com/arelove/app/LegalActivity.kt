package com.arelove.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arelove.app.databinding.ActivityLegalBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla legal de Arelove: muestra Términos, Política de privacidad,
 * Reglas de la comunidad o FAQ (markdown de assets convertido a HTML).
 * Recibe el extra "tipo": "terminos" | "privacidad" | "comunidad" | "faq".
 */
class LegalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tipo = intent.getStringExtra(EXTRA_TIPO) ?: "terminos"

        val nombreArchivo = when (tipo) {
            "privacidad" -> "legal/privacidad.md"
            "comunidad" -> "legal/comunidad.md"
            "faq" -> "legal/faq.md"
            else -> "legal/terminos.md"
        }

        val tituloRes = when (tipo) {
            "privacidad" -> R.string.legal_privacidad_title
            "comunidad" -> R.string.legal_comunidad_title
            "faq" -> R.string.legal_faq_title
            else -> R.string.legal_terminos_title
        }
        binding.tvLegalTitle.text = getString(tituloRes)

        binding.btnCloseLegal.setOnClickListener { finish() }

        binding.webViewLegal.settings.javaScriptEnabled = false
        binding.webViewLegal.settings.loadWithOverviewMode = true
        binding.webViewLegal.settings.useWideViewPort = true

        binding.progressBarLegal.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val markdown = withContext(Dispatchers.IO) { leerAsset(nombreArchivo) }
                val html = convertirMarkdownAHtml(markdown)
                binding.progressBarLegal.visibility = View.GONE
                binding.webViewLegal.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                binding.progressBarLegal.visibility = View.GONE
                binding.webViewLegal.loadDataWithBaseURL(
                    null,
                    convertirMarkdownAHtml("# Error\nNo se pudo cargar el documento."),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }

    /** Lee un archivo de assets/ como texto. */
    private fun leerAsset(ruta: String): String {
        return assets.open(ruta).bufferedReader().use { it.readText() }
    }

    /** Convierte markdown básico a HTML con estilos del tema oscuro de la app. */
    private fun convertirMarkdownAHtml(md: String): String {
        val cuerpo = md
            // Headers
            .replace(Regex("(?m)^### (.+)$"), "<h3>$1</h3>")
            .replace(Regex("(?m)^## (.+)$"), "<h2>$1</h2>")
            .replace(Regex("(?m)^# (.+)$"), "<h1>$1</h1>")
            // Bold e italic
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
            // Listas
            .replace(Regex("(?m)^- (.+)$"), "<li>$1</li>")
            .replace(Regex("(?m)^\\d+\\. (.+)$"), "<li>$1</li>")
            // Saltos de línea dobles a párrafos
            .replace("\n\n", "</p><p>")
            // Saltos simples a <br>
            .replace("\n", "<br>")

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body {
                    background: #121012;
                    color: #F5ECEE;
                    font-family: sans-serif;
                    font-size: 15px;
                    line-height: 1.6;
                    padding: 16px 20px 60px 20px;
                    margin: 0;
                }
                h1 { color: #E53935; font-size: 22px; margin: 24px 0 12px 0; }
                h2 { color: #E53935; font-size: 18px; margin: 20px 0 10px 0; }
                h3 { color: #FFA4A2; font-size: 16px; margin: 16px 0 8px 0; }
                b, strong { color: #FFFFFF; }
                i, em { color: #D4C2C5; }
                li { margin-left: -10px; padding-left: 0; }
                ul, ol { padding-left: 20px; }
                p { margin: 12px 0; }
                a { color: #E53935; }
            </style>
            </head>
            <body><p>$cuerpo</p></body>
            </html>
        """.trimIndent()
    }

    companion object {
        const val EXTRA_TIPO = "tipo"
    }
}
