# Velo Browser R8 & ProGuard Optimization Rules

# Preserve stack trace readability & Kotlin/Compose/Firestore generics metadata
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile

# Keep Firestore document POJOs & serialization models used via reflection
-keep class com.example.data.remote.** { *; }
-keepclassmembers class com.example.data.remote.** {
    public <init>(...);
    public *;
}

# Keep Room entities, DAOs, and generated database classes
-keep class com.example.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Jetpack Credential Manager & Google Identity Services
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Keep Firebase Auth & Firestore classes accessed reflectively
-keepattributes *Annotation*
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.firebase.auth.** { *; }

# OkHttp & Okio safe R8 rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Strip verbose/debug logging calls in release builds for faster startup & smaller APK
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
