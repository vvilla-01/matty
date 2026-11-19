# kotlinx.serialization ships its own consumer rules; keep our @Serializable models' names stable.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class ceo.villa.matty.**$$serializer { *; }
-keepclassmembers class ceo.villa.matty.** {
    *** Companion;
}
-keepclasseswithmembers class ceo.villa.matty.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp platform shims
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# libwebrtc (VillaNET streams) brings no keep rules of its own. Its native code finds these
# classes, their fields and the methods it calls back by name: R8 must neither rename nor drop
# any of them, or a stream crashes in release builds only.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
# Only JniZero.setJniClassLoader refers to it, which libwebrtc never calls; the build leaves it out.
-dontwarn org.jni_zero.JniZeroJni
