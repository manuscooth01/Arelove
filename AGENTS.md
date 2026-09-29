# AGENTS.md

App de citas Android (Kotlin + XML + Material 3) contra Supabase. Un único módulo Gradle (`:app`), sin tests.

## Verificación

- El único comando de verificación es `./gradlew assembleDebug`. **No hay tests, ni lint/detekt/ktlint** configurados.
- **Aquí no se puede compilar**: no hay Android SDK (ni `local.properties`, ni `ANDROID_HOME`) y la firma necesita secretos. AGP fallará con "SDK location not found". Verifica de forma estática (XML bien formados, ids de ViewBinding, firmas de API contra el AAR) y deja el build a CI.
- CI: `.github/workflows/build.yml` corre en cada push/PR a `main` con JDK 17 y sube el APK como artifact `arelove-debug-apk`.
- Firmado: `app/build.gradle` lee `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` del entorno (defaults: alias `arelove`, contraseñas vacías, fichero `arelove.keystore`). El keystore **no está en el repo** (gitignored) y su contraseña no está vacía → sin esas variables el build falla. CI lo decodifica de `secrets.KEYSTORE_BASE64`.
- **No** reemplaces `debug { signingConfig signingConfigs.release }` por la keystore de debug por defecto: el login con Google (Credential Manager) valida el SHA-1 de esa keystore contra el `WEB_CLIENT_ID` hardcodeado en `GoogleAuthHelper`.
- Release usa `minifyEnabled true`; keep rules en `app/proguard-rules.pro`.
- `settings.gradle` fija `RepositoriesMode.FAIL_ON_PROJECT_REPOS`: no añadas repositorios en `app/build.gradle`.

## Supabase (BOM 2.1.0) — trampas reales

- El *group* de Maven es `io.github.jan-tennert.supabase`, pero **los paquetes Kotlin son `io.github.jan.supabase.*`**: `io.github.jan.supabase.gotrue.auth`, `io.github.jan.supabase.postgrest.postgrest`. El módulo de Auth se llama `gotrue-kt`.
- Cliente único: `SupabaseClient.client` (URL y anon key hardcodeados; instala Auth, Postgrest, Realtime y Storage).
- Select con filtro: `client.postgrest["profiles"].select { filter { eq("id", userId) } }` — el lambda es de `PostgrestRequestBuilder` (ahí viven `filter`, `limit`, `single`), y `eq` está dentro de `filter`.
- **`decodeSingleOrNull<Map<String, Any>>()` no funciona**: `KotlinXSerializer` hace `serializer(typeOf<T>())` y kotlinx no serializa `Any`. Usa `decodeSingleOrNull<JsonObject>()`.
- **`upsert(mapOf(...))` tampoco** (explota en runtime con `SerializationException`). Usa `upsert(buildJsonObject { put("col", valor) })`.
- `age` se envía como número (`put("age", edad)`), no como texto.
- Metadata del usuario: `auth.currentUserOrNull()?.userMetadata` es `JsonObject?` → `(it["nickname"] as? JsonPrimitive)?.content`. Siempre `awaitInitialization()` antes de leer la sesión.
- Una Activity nueva debe declararse en `AndroidManifest.xml`; si no, `ActivityNotFoundException`.

## Flujo de pantallas

- `MainActivity` (launcher) → `awaitInitialization()` → con sesión consulta `profiles` y abre `ProfileSetupActivity` si el perfil no tiene `age`+`location`+`looking_for`, o `HomeActivity` si está completo; sin sesión a Login/Register.
- `RegisterActivity` abre `ProfileSetupActivity` con `NEW_TASK | CLEAR_TASK` tras registrarse.
- `LoginActivity` va directo a `HomeActivity` (no comprueba el perfil; solo lo hace `MainActivity`).
- Asistente de perfil: 5 pasos en `ViewPager2` con `isUserInputEnabled = false` (paquete `com.arelove.app.profile`). Solo el paso 1 valida y guarda; los pasos 2-5 son placeholders.

## Convenciones del repo

- Comentarios y strings **en español**; todos los textos en `res/values/strings.xml` (nada hardcodeado en layouts ni en código).
- ViewBinding obligatorio (no hay Compose ni data binding). En fragments: `_binding` nullable + `null` en `onDestroyView`.
- Errores de formulario inline en `TextInputLayout.error`; avisos con `AreloveDialog.show(...)` — nunca Toast.
- Tema único `Theme.Arelove` con variantes `values/` y `values-night/`; colores de marca en `res/values/colors.xml`.
- `android.nonTransitiveRClass=true`: para recursos de librerías usa el R completo (p. ej. `com.google.android.material.R...`).

## Entorno

- Termux (aarch64), JDK 17, wrapper Gradle 8.2 ya cacheado. AGP 8.2.2 / Kotlin 1.9.22 / compileSdk 34 / minSdk 29 / Java 17.
- Los APK descargados viven en `apk/` (gitignored); `*.keystore` y `*.base64` también.
