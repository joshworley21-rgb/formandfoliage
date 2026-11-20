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
            // arm64 only: every phone this runs on is 64-bit, and dropping
            // the 32-bit copies removes ~25 MB of native speech libraries.
            abiFilters += listOf("arm64-v8a")
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
        // No OpenAI key: cloud-routed voice turns go through the voice-route
        // Edge Function, which holds the key in Supabase secrets.
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
    implementation(libs.okhttp)
    // Extracts the optional fp32 Kokoro model from its .tar.bz2 release.
    implementation(libs.commons.compress)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    // MediaSessionCompat: the head-unit and headset call button during a call.
    implementation(libs.androidx.media)
    // The NDK module is excluded: 7.14.0's startup (native signal handling and
    // auto-init providers) killed the app before our code ran on the owner's
    // phone. Java crash reporting needs none of it.
    implementation(libs.sentry.android) {
        exclude(group = "io.sentry", module = "sentry-android-ndk")
    }

    // Offline speech-to-text for the Assistant tab. sherpa-onnx is not on
    // Maven Central: the official AAR is fetched by :app:downloadSherpa.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // Phonon 31m TTS calls libptts_ffi.so through JNA. The library itself is
    // built by the APK workflow (Rust + NDK) into src/main/jniLibs.
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.exifinterface)
    // On-device LLM runtime for the free "local thinking" fallback.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// --- Offline speech binaries -------------------------------------------------
// The sherpa-onnx AAR is not committed; this task downloads it when missing
// (or when the checksum no longer matches). preBuild depends on it, so a fresh
// clone only needs network access once. The speech models are downloaded from
// within the app instead of bundled.
val sherpaDownloads: Map<String, Pair<String, String>> = mapOf(
    "libs/sherpa-onnx-1.13.8.aar" to (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar" to
            "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
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
    description = "Downloads the sherpa-onnx AAR if missing."
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

// --- Kokoro-82M offline TTS -------------------------------------------------
// One 127 MB archive holds the int8 multilingual model (English + Chinese),
// the 53 built-in voices — af_heart is speaker 3 — tokens, the English
// lexicon and espeak-ng-data. Only the English pieces are extracted; the
// Chinese dictionaries and FSTs are skipped.
val kokoroDir = "src/main/assets/kokoro-multi-lang-v1_0"
val kokoroArchiveSha = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"
val kokoroFiles: Map<String, String> = mapOf(
    "model.int8.onnx" to "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e",
    "voices.bin" to "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
    "tokens.txt" to "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
    "lexicon-us-en.txt" to "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14",
    "lexicon-gb-en.txt" to "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7",
)

fun download(url: String, target: File, expectedSha: String) {
    logger.lifecycle("download: $url")
    target.parentFile?.mkdirs()
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
    check(sha256(partial) == expectedSha) { "Checksum mismatch for $url" }
    if (!partial.renameTo(target)) {
        partial.copyTo(target, overwrite = true)
        partial.delete()
    }
}

val downloadKokoroArchive by tasks.registering {
    group = "build"
    description = "Downloads the Kokoro-82M int8 model archive."
    val archive = layout.buildDirectory.file("downloads/kokoro-int8-multi-lang-v1_0.tar.bz2")
    outputs.file(archive)
    doLast {
        val target = archive.get().asFile
        if (target.isFile && sha256(target) == kokoroArchiveSha) return@doLast
        download(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            target,
            kokoroArchiveSha,
        )
    }
}

val downloadKokoro by tasks.registering(Exec::class) {
    group = "build"
    description = "Extracts the Kokoro model, af_heart voice bank, lexicon and espeak-ng-data into assets."
    dependsOn(downloadKokoroArchive)
    val root = layout.projectDirectory.dir(kokoroDir).asFile
    val archive = layout.buildDirectory.file("downloads/kokoro-int8-multi-lang-v1_0.tar.bz2")
    inputs.file(archive)
    outputs.dir(root)

    fun complete(): Boolean =
        File(root, "espeak-ng-data/phontab").isFile &&
            kokoroFiles.all { (name, sha) -> File(root, name).let { it.isFile && sha256(it) == sha } }

    onlyIf { !complete() }
    doFirst { root.mkdirs() }
    commandLine(
        "tar",
        "-xjf",
        archive.get().asFile.absolutePath,
        "-C",
        root.absolutePath,
        "--strip-components=1",
        "kokoro-int8-multi-lang-v1_0/model.int8.onnx",
        "kokoro-int8-multi-lang-v1_0/voices.bin",
        "kokoro-int8-multi-lang-v1_0/tokens.txt",
        "kokoro-int8-multi-lang-v1_0/lexicon-us-en.txt",
        "kokoro-int8-multi-lang-v1_0/lexicon-gb-en.txt",
        "kokoro-int8-multi-lang-v1_0/espeak-ng-data",
    )
    doLast {
        kokoroFiles.forEach { (name, sha) ->
            check(sha256(File(root, name)) == sha) { "Checksum mismatch after extracting $name" }
        }
        check(File(root, "espeak-ng-data/phontab").isFile) { "espeak-ng-data missing from the archive" }
    }
}


// --- local voice packs bundled into the APK -------------------------------
//
// Downloading and unpacking a 20-160 MB voice on the phone is slow, especially
// on site. CI has the bandwidth and disk, so the build fetches the offered
// voices, unpacks them into generated assets, and the app only has to copy one
// folder into its files directory when the owner picks it. Set
// -PbundleVoices=false to skip this (unit tests do; they need no voices).
data class BundledVoice(val id: String, val bytes: Long)

val bundledVoices = buildList {
    add(BundledVoice("sherpa-onnx-pocket-tts-int8-2026-01-26", 98_336_520L))
    add(BundledVoice("kitten-nano-en-v0_8-int8", 31_220_690L))
    add(BundledVoice("sherpa-onnx-supertonic-3-tts-int8-2026-05-11", 128_774_318L))
    add(BundledVoice("vits-piper-en_GB-cori-high", 115_574_061L))
    add(BundledVoice("vits-piper-en_GB-alan-medium-int8", 21_103_831L))
    add(BundledVoice("vits-piper-en_GB-jenny_dioco-medium-int8", 20_950_036L))
    // Pocket fp32 is 451 MB unpacked: it stays a download-only option unless
    // a build explicitly asks for the extra size.
    if (providers.gradleProperty("bundlePocketHq").orNull == "true") {
        add(BundledVoice("sherpa-onnx-pocket-tts-2026-01-26", 168_148_625L))
    }
}

val voiceAssetsDir = layout.buildDirectory.dir("generated/voiceAssets")
val voiceArchiveDir = layout.buildDirectory.dir("voiceArchives")

val bundleVoices = tasks.register("bundleVoices") {
    description = "Download and unpack the local TTS voices into APK assets."
    group = "build"
    outputs.dir(voiceAssetsDir)

    doLast {
        val assets = voiceAssetsDir.get().asFile
        val archives = voiceArchiveDir.get().asFile
        assets.mkdirs()
        archives.mkdirs()

        fun download(url: String, target: File, expected: Long) {
            if (target.isFile && target.length() == expected) return
            val part = File(target.parentFile, "${target.name}.part")
            part.delete()
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent", "formandfoliage-build")
            connection.inputStream.use { input ->
                part.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            check(part.length() == expected) {
                "Voice download ${target.name} was ${part.length()} bytes, expected $expected"
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        }

        bundledVoices.forEach { voice ->
            // Runtime reads assets/voices/<id>, so the APK path must match.
            val destination = File(assets, "voices/${voice.id}")
            if (File(destination, ".bundled").isFile) return@forEach

            val archive = File(archives, "${voice.id}.tar.bz2")
            download(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                    "${voice.id}.tar.bz2",
                archive,
                voice.bytes,
            )

            val unpacked = File(voiceAssetsDir.get().asFile.parentFile, "voiceUnpack/${voice.id}")
            unpacked.deleteRecursively()
            unpacked.mkdirs()
            val exit = ProcessBuilder(
                "tar", "xjf", archive.absolutePath, "-C", unpacked.absolutePath,
            ).inheritIO().start().waitFor()
            check(exit == 0) { "Could not unpack ${archive.name}" }

            val root = unpacked.listFiles()?.firstOrNull { it.isDirectory } ?: unpacked

            // Pocket is a voice-cloning model; keep the demo reference clip it
            // clones, once, at assets/voices/pocket-reference.wav.
            if (voice.id.startsWith("sherpa-onnx-pocket-tts")) {
                val reference = File(root, "test_wavs/bria.wav")
                if (reference.isFile) {
                    val output = File(assets, "voices/pocket-reference.wav")
                    output.parentFile?.mkdirs()
                    reference.copyTo(output, overwrite = true)
                }
            }

            destination.deleteRecursively()
            root.copyRecursively(destination, overwrite = true)
            unpacked.deleteRecursively()
            // Sample wavs and licence text only waste APK space.
            File(destination, "test_wavs").deleteRecursively()
            listOf("README.md", "LICENSE", ".gitattributes").forEach {
                File(destination, it).delete()
            }
            File(destination, ".bundled").writeText("1")
        }
    }
}

if (providers.gradleProperty("bundleVoices").orNull == "true") {
    android.sourceSets.getByName("main").assets.srcDir(voiceAssetsDir)
    tasks.named("preBuild") { dependsOn(bundleVoices) }
}


afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(downloadSherpa)
        dependsOn(downloadKokoro)
    }
}
