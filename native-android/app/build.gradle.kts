import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "uk.co.jwgardenservices.crm"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.jwgardenservices.crm"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx ships native libraries for four ABIs (122 MB). Phones
        // need only the two ARM ones; dropping x86 halves the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Public client configuration, overridable from gradle.properties or a
        // local.properties file. These are not secrets: RLS protects the data.
        val supabaseUrl = providers.gradleProperty("SUPABASE_URL")
            .orElse("https://ugbjwgfmsrkclrzpfwgz.supabase.co")
            .get()
        val supabaseKey = providers.gradleProperty("SUPABASE_PUBLISHABLE_KEY")
            .orElse("sb_publishable_QhXCtI1-8WqzxTHE_u9R3Q_eJTzacF4")
            .get()

        // Public client key, overridable via a SENTRY_DSN property or GitHub secret.
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"" + (providers.gradleProperty("SENTRY_DSN").orNull?.takeIf { it.isNotBlank() }
                ?: "https://2db5e3b800989fa06211896264228199@o4512413257826304.ingest.de.sentry.io/4512413289087056") + "\"",
        )
        // Voice input runs entirely on the phone with sherpa-onnx, so there is
        // no server URL or token to inject here any more.
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "AUTH_SCHEME", "\"uk.co.jwgardenservices.crm\"")
        buildConfigField("String", "AUTH_HOST", "\"password-reset\"")
        buildConfigField(
            "String",
            "AUTH_REDIRECT",
            "\"uk.co.jwgardenservices.crm://password-reset\"",
        )
    }

    buildTypes {
        release {
            // Back off while the first R8 build is validated on a device; a
            // broken strip crashes at startup, which is worse than a larger APK.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API 24–25, which the date arithmetic relies on.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    // The NDK module is excluded: 7.14.0's startup (native signal handling and
    // auto-init providers) killed the app before our code ran on the owner's
    // phone. Java crash reporting needs none of it.
    implementation(libs.sentry.android) {
        exclude(group = "io.sentry", module = "sentry-android-ndk")
    }

    // Offline speech-to-text for the Assistant tab. sherpa-onnx is not on
    // Maven Central: the official AAR is fetched by :app:downloadSherpa.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // On-device intent interpretation (llama.cpp JNI, arm64-v8a). A prebuilt
    // AAR keeps the NDK out of the build; grammar support is documented in
    // QwenIntentEngine.kt.
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.exifinterface)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// --- Offline speech binaries -------------------------------------------------
// The sherpa-onnx AAR and the streaming Zipformer model are ~92 MB together,
// so they are not committed. This task downloads whichever file is missing (or
// whose checksum no longer matches). preBuild depends on it, so a fresh clone
// only needs network access once.
val sherpaModelDir = "src/main/assets/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
val sherpaDownloads: Map<String, Pair<String, String>> = mapOf(
    "libs/sherpa-onnx-1.13.8.aar" to (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar" to
            "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        ),
    "$sherpaModelDir/encoder-epoch-99-avg-1.int8.onnx" to (
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main/encoder-epoch-99-avg-1.int8.onnx" to
            "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"
        ),
    "$sherpaModelDir/decoder-epoch-99-avg-1.onnx" to (
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main/decoder-epoch-99-avg-1.onnx" to
            "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30"
        ),
    "$sherpaModelDir/joiner-epoch-99-avg-1.int8.onnx" to (
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main/joiner-epoch-99-avg-1.int8.onnx" to
            "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"
        ),
    "$sherpaModelDir/tokens.txt" to (
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main/tokens.txt" to
            "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"
        ),
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadSherpa by tasks.registering {
    group = "build"
    description = "Downloads the sherpa-onnx AAR and streaming Zipformer model if missing."
    val root = layout.projectDirectory
    outputs.files(sherpaDownloads.keys.map { root.file(it).asFile })
    doLast {
        sherpaDownloads.forEach { (path, download) ->
            val (url, expected) = download
            val target = root.file(path).asFile
            if (target.isFile && sha256(target) == expected) return@forEach

            logger.lifecycle("downloadSherpa: fetching $path")
            target.parentFile.mkdirs()
            val partial = File(target.parentFile, "${target.name}.part")
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.setRequestProperty("User-Agent", "jw-crm-gradle")
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("HTTP $code while downloading $url")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            check(sha256(partial) == expected) { "Checksum mismatch for $url" }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") { dependsOn(downloadSherpa) }
}
