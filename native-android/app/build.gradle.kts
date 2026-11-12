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
        // Self-hosted LiveKit on the VPS: the assistant joins the same room as
        // the bm_fable pipeline. --dev-mode credentials, overridable by property.
        val livekitUrl = providers.gradleProperty("LIVEKIT_URL")
            .orElse("wss://45.13.232.205:7880").get()
        val livekitRoom = providers.gradleProperty("LIVEKIT_ROOM").orElse("assistant").get()
        val livekitToken = providers.gradleProperty("LIVEKIT_TOKEN")
            .orElse("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJkZXZrZXkiLCJleHAiOjE5MDAwMDAwMDAsImlkZW50aXR5IjoiY3JtLWFzc2lzdGFudC1jbGllbnQiLCJuYW1lIjoiQ3JtIFBob25lIiwicm9vbUpvaW4iOnsicm9vbSI6ImFzc2lzdGFudCIsInJvb21Kb2luIjp0cnVlfSwibWV0YWRhdGEiOiIifQ.AufrPkD2sDjzA8-oOV3nXcEDZvvbHS0BEG2cbHTbxO4")
        buildConfigField("String", "LIVEKIT_URL", "\"$livekitUrl\"")
        buildConfigField("String", "LIVEKIT_ROOM", "\"$livekitRoom\"")
        buildConfigField("String", "LIVEKIT_TOKEN", "\"$livekitToken\"")
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

    // LiveKit room for the server-brain assistant call.
    implementation(libs.livekit.android)

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
