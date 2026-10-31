# Reglas ProGuard/R8 para Arelove.
# Mantener las clases de Supabase y Ktor al minificar la release.
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
