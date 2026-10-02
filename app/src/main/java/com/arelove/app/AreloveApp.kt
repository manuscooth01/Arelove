package com.arelove.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.arelove.app.notifications.OneSignalManager

/**
 * Application de Arelove: declara en AndroidManifest (`android:name=".AreloveApp"`)
 * y configura la caché global de Coil.
 *
 * Coil usa este [ImageLoaderFactory] para su singleton, así que todas las
 * `ImageView.load(...)` de la app (fotos del paso 3, selfie del paso 5,
 * Home) comparten memoria (15% de la RAM) y disco (50 MB en `cacheDir/coil`),
 * en vez de usar los valores por defecto.
 */
class AreloveApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // OneSignal: registra el SDK y pide el permiso de notificaciones
        OneSignalManager.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil"))
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
