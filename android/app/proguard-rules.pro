# kotlinx.serialization — keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.clawbridge.mobile.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.clawbridge.mobile.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.clawbridge.mobile.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp — platform classes referenced reflectively
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
