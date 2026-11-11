# Keep Kotlinx serialization metadata for the Supabase DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class uk.co.jwgardenservices.crm.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class uk.co.jwgardenservices.crm.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
