# JNA uses reflection and native callbacks extensively; keep its runtime intact.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# The chess library's JNA bindings are resolved reflectively by name.
-keep class com.nachogoro.simplechess.** { *; }

# SCP protocol model types are plain data holders; keep names for readable diagnostics.
-keep class scp.protocol.** { *; }
