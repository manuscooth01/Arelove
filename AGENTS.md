# AGENTS.md

App de citas Android (Kotlin + XML + Material 3) contra Supabase. Un único módulo Gradle (`:app`), sin tests.

## Verificación

- El único comando de verificación es `./gradlew assembleDebug`. **No hay tests, ni lint/detekt/ktlint** configurados.
- **Aquí no se puede compilar**: no hay Android SDK (ni `local.properties`, ni `ANDROID_HOME`) y la firma necesita secretos. AGP fallará con "SDK location not found". Verifica de forma estática (XML bien formados, ids de ViewBinding, firmas de API contra el AAR) y deja el build a CI.
- CI: `.github/workflows/build.yml` corre en cada push/PR a `main` con JDK 17 y sube el APK como artifact `arelove-debug-apk`. En un run fallido, los errores reales de Kotlin (`e: file:///...`) quedan antes del stacktrace de Gradle: `gh run view <ID> --log-failed | grep 'error:'`.
- Firmado: `app/build.gradle` lee `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` del entorno (defaults: alias `arelove`, contraseñas vacías, fichero `arelove.keystore`). El keystore **no está en el repo** (gitignored) y su contraseña no está vacía → sin esas variables el build falla. CI lo decodifica de `secrets.KEYSTORE_BASE64`.
- **No** reemplaces `debug { signingConfig signingConfigs.release }` por la keystore de debug por defecto: el login con Google (Credential Manager) valida el SHA-1 de esa keystore contra el `WEB_CLIENT_ID` hardcodeado en `GoogleAuthHelper`.
- Release usa `minifyEnabled true`; keep rules en `app/proguard-rules.pro`.
- `settings.gradle` fija `RepositoriesMode.FAIL_ON_PROJECT_REPOS`: no añadas repositorios en `app/build.gradle`.

## Supabase (BOM 2.1.0) — trampas reales

- El *group* de Maven es `io.github.jan-tennert.supabase`, pero **los paquetes Kotlin son `io.github.jan.supabase.*`**: `io.github.jan.supabase.gotrue.auth`, `io.github.jan.supabase.postgrest.postgrest`. El módulo de Auth se llama `gotrue-kt`.
- Cliente único: `SupabaseClient.client` (URL y anon key hardcodeados; instala Auth, Postgrest y Storage; **no** Realtime). `SUPABASE_URL` es `internal` para usarla al construir URLs absolutas.
- Select con filtro: `client.postgrest["profiles"].select { filter { eq("id", userId) } }` — el lambda es de `PostgrestRequestBuilder` (ahí viven `filter`, `limit`, `single`), y `eq` está dentro de `filter`.
- **`decodeSingleOrNull<Map<String, Any>>()` no funciona**: `KotlinXSerializer` hace `serializer(typeOf<T>())` y kotlinx no serializa `Any`. Usa `decodeSingleOrNull<JsonObject>()`.
- **`upsert(mapOf(...))` tampoco** (explota en runtime con `SerializationException`). Usa `upsert(buildJsonObject { put("col", valor) })`.
- `age` se envía como número (`put("age", edad)`), no como texto.
- Metadata del usuario: `auth.currentUserOrNull()?.userMetadata` es `JsonObject?` → `(it["nickname"] as? JsonPrimitive)?.content`. Siempre `awaitInitialization()` antes de leer la sesión.
- Storage: para `client.storage[bucket]` hace falta `import io.github.jan.supabase.storage.storage` (propiedad de extensión; sin ella: `Unresolved reference: storage`). Buckets: `avatars` (público, fotos del paso 3) y `verification` (privado, selfie del paso 5).
- **`createSignedUrl()` devuelve una ruta RELATIVA** (`object/sign/...`, sin host): hay que anteponer `SupabaseClient.SUPABASE_URL` (ya lo hace `PhotoUploadHelper.urlFirmada()`). Coil no puede cargar una URL relativa; si falla, el fallback es `storage[bucket].downloadAuthenticated(path): ByteArray`.
- Firmas de la SDK: verifícalas con `javap -c -p -classpath <cls>` sobre las clases extraídas del AAR en `~/.gradle/caches/modules-2/files-2.1/io.github.jan-tennert.supabase/...` (descomprime `classes.jar`).
- Una Activity nueva debe declararse en `AndroidManifest.xml`; si no, `ActivityNotFoundException`.

## Flujo de pantallas

- `MainActivity` (launcher) → `awaitInitialization()` → con sesión consulta `profiles` y abre `ProfileSetupActivity` si el perfil no tiene `age`+`location`+`looking_for`, o `HomeActivity` si está completo; sin sesión a Login/Register.
- `RegisterActivity` abre `ProfileSetupActivity` con `NEW_TASK | CLEAR_TASK` tras registrarse.
- `LoginActivity` navega SIEMPRE a `MainActivity` con `NEW_TASK|CLEAR_TASK` tras login exitoso. Es `MainActivity` quien decide entre `ProfileSetupActivity` (perfil incompleto) y `HomeActivity` (perfil completo) usando `ProfileChecker.perfilCompleto()`. Se centralizó así para evitar una consulta de red duplicada.
- Asistente de perfil: 5 pasos en `ViewPager2` con `isUserInputEnabled = false` (paquete `com.arelove.app.profile`). **Los 5 pasos** implementan `validarYGuardar(onSuccess, onError)` y validan con reglas propias (p. ej. paso 2 exige 3 intereses, paso 3 exige 2 fotos, paso 5 sube la selfie o salta con diálogo).

## Convenciones del repo

- Comentarios y strings **en español**; todos los textos en `res/values/strings.xml` (nada hardcodeado en layouts ni en código).
- ViewBinding obligatorio (no hay Compose ni data binding). En fragments: `_binding` nullable + `null` en `onDestroyView`.
- Errores de formulario inline en `TextInputLayout.error`; avisos con `AreloveDialog.show(...)` — nunca Toast.
- Tema único `Theme.Arelove` con variantes `values/` y `values-night/`; colores de marca en `res/values/colors.xml`.
- `android.nonTransitiveRClass=true`: para recursos de librerías usa el R completo (p. ej. `com.google.android.material.R...`).

## Sistema de diseño (`res/values/styles.xml`)

- El tema ya aplica por defecto: Poppins global (`@font/poppins`), `materialButtonStyle`=Primary, `textInputStyle`=TextInput, `chipStyle`=Chip, `cardViewStyle`=Card. **No repitas esos defaults en cada layout**; solo declara un `style=`/`textAppearance=` cuando quieras otra variante (p. ej. Outlined).
- Fuentes en `res/font/` (`poppins_regular`, `poppins_medium`, `poppins_semibold`, `poppins_bold`). `grep -rn sans-serif app/src/main/res/layout/` debe dar **0**: `android:fontFamily="@font/poppins_semibold"` solo en títulos y botones; el cuerpo no lleva atributo (hereda Poppins del tema).
- Patrón por layout: títulos → `textAppearance="@style/Widget.Arelove.Title"` + `fontFamily` semibold; subtítulos → `textAppearance="@style/Widget.Arelove.Subtitle"` sin `fontFamily`; `TextInputLayout` → `style="@style/Widget.Arelove.TextInput"`; botones → `Widget.Arelove.Button.Primary` / `.Outlined`.
- Excepción: los `TextInputLayout` con `...OutlinedBox.ExposedDropdownMenu` (`tilLocation` paso 1, `tilZodiac` paso 4) conservan el estilo Material3 — no hay variante Arelove y cambiarla rompe el icono desplegable.
- `activity_login.xml` es la plantilla de referencia de todo lo anterior; `HomeActivity.kt` solo usa los ids `tvEmail` y `btnLogout` de su layout (renovar `activity_home.xml` es seguro si se conservan).

## Optimizaciones aplicadas

Para no reintroducir regresiones de rendimiento, estas optimizaciones ya están aplicadas (no deshacer sin medir):

- Listeners de `MainActivity` se instalan antes de cualquier red (botones responden desde el primer frame).
- Cliente Supabase se precarga en `Dispatchers.IO`.
- Login navega a `MainActivity` (una sola comprobación de perfil, no dos).
- `windowBackground` del tema pinta el fondo; los layouts raíz ya NO repiten `android:background` (evita overdraw).
- `ViewPager2.offscreenPageLimit = 1` en el asistente (no reinflar pasos).
- Sin dependencias sin uso: no existen Realtime/ViewModel/ConstraintLayout; Coil y Storage sí se usan (preview de la selfie del paso 5 y subida de fotos).

## Entorno

- Termux (aarch64), JDK 17, wrapper Gradle 8.2 ya cacheado. AGP 8.2.2 / Kotlin 1.9.22 / compileSdk 34 / minSdk 29 / Java 17.
- Los APK descargados viven en `apk/` (gitignored); `*.keystore` y `*.base64` también.
