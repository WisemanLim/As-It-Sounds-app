# UniFFI Kotlin bindings rely on JNA — keep all JNA classes
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# Keep our generated FFI surface
-keep class com.asitsounds.core.** { *; }
-keepclassmembers class com.asitsounds.core.** { *; }
