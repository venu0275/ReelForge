# Release builds are shrunk and obfuscated with R8 (Play's "DEX code optimization" check).
# Libraries ship their own rules; these cover the ones that call into Java from native code.

# Keep file names and line numbers in crash reports (Play de-obfuscates with the mapping file
# that is uploaded inside the app bundle).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Vosk speech recognition (Auto captions) talks to its native library through JNA, which finds
# classes, methods and fields by name.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# LiteRT (Remove background) is called from its native code.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
