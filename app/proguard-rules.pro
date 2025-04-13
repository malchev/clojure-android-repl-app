# Completely disable all obfuscation for reliability
-dontobfuscate
-dontoptimize
-dontshrink
-keepattributes *

# Keep all Clojure classes with all members and attributes
-keep class clojure.** { *; }
-keepclassmembers class clojure.** { *; }
-dontwarn clojure.**

# Keep all Clojure interfaces
-keep interface clojure.** { *; }
-keepclassmembers interface clojure.** { *; }

# Protect dynamically generated Clojure classes (all patterns)
-keep class **$eval** { *; }
-keep class **.proxy$** { *; }
-keep class **$fn__** { *; }
-keep class clojure.core$eval** { *; }
-keep class clojure.core$proxy** { *; }
-keep class clojure.core$fn__** { *; }
-keep class user$eval** { *; }
-keep class user$proxy** { *; }
-keep class user$fn__** { *; }
-keepclassmembers class **$eval** { *; }
-keepclassmembers class **.proxy$** { *; }
-keepclassmembers class **$fn__** { *; }
-keepclassmembers class clojure.core$eval** { *; }
-keepclassmembers class clojure.core$proxy** { *; }
-keepclassmembers class clojure.core$fn__** { *; }
-keepclassmembers class user$eval** { *; }
-keepclassmembers class user$proxy** { *; }
-keepclassmembers class user$fn__** { *; }

# General rule for any dynamic Clojure classes
-keep class **.clojure$** { *; }
-keepclassmembers class **.clojure$** { *; }

# Protect all Clojure proxy targets
-keep class * implements clojure.lang.IProxy { *; }

# Keep Clojure compile-time constants
-keepclasseswithmembers class * {
    public static final clojure.lang.Var const__*;
}

# Keep constructors of any classes used in proxy
-keepclassmembers class * {
    public <init>(...);
    protected <init>(...);
    private <init>(...);
}

# Important reflection-related attributes
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses,EnclosingMethod
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleAnnotations
-keepattributes MethodParameters,Exceptions

# Google Play Services - preserve all classes
-keep class com.google.android.gms.** { *; }
-keepclassmembers class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-keepclassmembers interface com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Preserve all Android classes that might be used via proxy
-keep class android.** { *; }
-keepclassmembers class android.** { *; }
-keep interface android.** { *; }
-keepclassmembers interface android.** { *; }

# Keep AndroidX classes for proxy usage
-keep class androidx.** { *; }
-keepclassmembers class androidx.** { *; }
-keep interface androidx.** { *; }
-keepclassmembers interface androidx.** { *; }

# GSON specific rules
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }

# Ignore warnings about missing JDK classes used by R8 itself
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.processing.**
-dontwarn javax.tools.**
-dontwarn javax.management.**
-dontwarn com.sun.management.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Ignore warnings for ktor/jetty/kotlin libraries
-dontwarn io.ktor.**
-dontwarn org.eclipse.jetty.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Ignore warnings for R8 internal classes
-dontwarn com.android.tools.r8.**
-dontwarn com.android.tools.r8.internal.**
