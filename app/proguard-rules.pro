# R8 / ProGuard rules.
#
# NOTE ON THE CURRENT BUILD: the release build type sets `optimization { enable = false }`, so
# nothing here is being applied to shrink or rename anything today. These rules are checked in
# ahead of that because of what happens the moment optimization IS enabled, which is described
# below. They are cheap now and impossible to reconstruct after the fact from a field report.

# ---------------------------------------------------------------------------------------------
# Navigation back-stack restore (process-death fix).
#
# WHY: androidx.navigation3's NavKeySerializer persists a route by writing
# `value::class.java.name` as a string, and restores it with `Class.forName(className)`. The
# fully qualified class name IS the wire format of every saved back stack.
#
# If R8 renames a route class, every previously saved stack becomes undeserializable. The app
# does not crash (the saver catches it and falls back to Home by design), which is exactly what
# makes this dangerous: restore silently stops working for every worker on every device, forever,
# and no crash report is ever filed. Silent permanent breakage is worse than a loud failure.
-keep class com.example.samdapp.presentation.navigation.** { *; }

# The generated kotlinx.serialization serializers for those routes, reached reflectively by
# `kotlin.serializer()` inside NavKeySerializer rather than by a direct reference R8 can see.
-keepclassmembers class com.example.samdapp.presentation.navigation.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.samdapp.presentation.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.samdapp.presentation.navigation.**$$serializer { *; }
