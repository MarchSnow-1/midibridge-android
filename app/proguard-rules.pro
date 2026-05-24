# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Gson
-keep class com.marchsnow.midibridge.data.** { *; }
-keepattributes SerializedName
