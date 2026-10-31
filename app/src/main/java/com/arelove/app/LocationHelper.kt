package com.arelove.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Obtiene la ubicación del usuario con FusedLocationProvider y la convierte
 * en texto legible con el Geocoder nativo (sin API key de Google Maps).
 */
object LocationHelper {

    /** Resultado de una fijación: ciudad legible y coordenadas del punto. */
    data class UbicacionActual(
        val ciudad: String?,
        val latitude: Double,
        val longitude: Double
    )

    /** Coordenadas sueltas, para subirlas a la tabla `profiles`. */
    data class Coordenadas(
        val latitude: Double,
        val longitude: Double
    )

    /**
     * Fija la ubicación actual y devuelve la ciudad (reverse geocoding) más
     * las coordenadas.
     *
     * @throws SecurityException si no hay permiso de ubicación (mensaje legible)
     * @throws Exception si el GPS / la red no devuelven ninguna posición
     */
    suspend fun obtenerUbicacionActual(context: Context): UbicacionActual {
        if (!tienePermiso(context)) {
            throw SecurityException(context.getString(R.string.location_permission_denied))
        }

        val ubicacion = pedirUbicacion(context)
            ?: throw Exception(context.getString(R.string.location_error))

        // El geocodificador hace I/O y puede tardar: se ejecuta en un dispatcher de fondo
        val ciudad = withContext(Dispatchers.IO) {
            reverseGeocoding(context, ubicacion.latitude, ubicacion.longitude)
        }

        return UbicacionActual(
            ciudad = ciudad,
            latitude = ubicacion.latitude,
            longitude = ubicacion.longitude
        )
    }

    /** Conveniencia: solo la ciudad, para rellenar el campo de lugar. */
    suspend fun obtenerCiudadActual(context: Context): String? =
        obtenerUbicacionActual(context).ciudad

    /** Conveniencia: solo las coordenadas, para guardarlas en Supabase. */
    suspend fun obtenerCoordenadas(context: Context): Coordenadas =
        obtenerUbicacionActual(context).let { Coordenadas(it.latitude, it.longitude) }

    /** true si el permiso fino o el grueso está concedido. */
    private fun tienePermiso(context: Context): Boolean {
        val fino = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val grueso = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fino || grueso
    }

    /**
     * Pide una única fijación de posición y espera a la [Task] con corrutinas.
     * El permiso ya está comprobado en [obtenerUbicacionActual].
     */
    @SuppressLint("MissingPermission")
    private suspend fun pedirUbicacion(context: Context): Location? {
        val cliente = LocationServices.getFusedLocationProviderClient(context)
        val token = CancellationTokenSource().token

        return suspendCancellableCoroutine { continuacion ->
            cliente
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token)
                .addOnSuccessListener { ubicacion ->
                    if (continuacion.isActive) continuacion.resume(ubicacion)
                }
                .addOnFailureListener {
                    if (continuacion.isActive) continuacion.resume(null)
                }
        }
    }

    /**
     * Convierte unas coordenadas en texto tipo "Charallave, Miranda, Venezuela".
     * Devuelve null si no hay geocodificador o si no hay resultados.
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocoding(context: Context, latitud: Double, longitud: Double): String? {
        if (!Geocoder.isPresent()) return null

        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            val direcciones: List<Address> =
                geocoder.getFromLocation(latitud, longitud, 1).orEmpty()
            direcciones.firstOrNull()?.let { textoLegible(it) }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** "Localidad, Estado, País"; si no, la primera línea de la dirección. */
    private fun textoLegible(direccion: Address): String? {
        val partes = listOfNotNull(
            direccion.locality ?: direccion.featureName,
            direccion.adminArea,
            direccion.countryName
        ).distinct()

        if (partes.isNotEmpty()) return partes.joinToString(", ")
        if (direccion.maxAddressLineIndex >= 0) return direccion.getAddressLine(0)
        return null
    }
}
