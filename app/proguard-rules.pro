# Hilt - keep @Inject sites so the generated DI code can find them.
-keepclassmembers,allowobfuscation class * {
  @javax.inject.Inject <fields>;
  @javax.inject.Inject <init>(...);
}

# Room - defensive: the database subclass and entity classes need to survive
# shrinking in case generated implementation reflects against them.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# Gson - generic-type info via reflection needs Signature; annotation-driven
# field naming needs *Annotation*. We deliberately do NOT add
# `-keep class com.google.gson.** { *; }` - Gson's AAR ships its own
# consumer-rules.pro that R8 applies automatically. Adding a blanket keep on
# top duplicates the library's intent and blocks legitimate shrinking.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# ML Kit references com.google.android.datatransport.* internally for its
# Firelog telemetry pipeline. We exclude that subgraph in app/build.gradle.kts
# (privacy: the app declares no INTERNET), so R8 sees dangling references it
# would otherwise abort on. The references sit on code paths that never run
# without the transport library, so silencing them is safe.
-dontwarn com.google.android.datatransport.**

# Argon2id - JNI bridge classes must survive shrinking and obfuscation.
# Without this, the JNI stubs get stripped and the native KDF crashes
# on the first call. This keep is required, not defensive.
-keep class com.lambdapioneer.argon2kt.** { *; }

# Coroutines - keep the dispatcher factories by name so debug-mode stack-trace
# recovery and the Main dispatcher loader can find them via reflection. Other
# coroutine internals are covered by kotlinx-coroutines' own consumer rules.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory

# Autofill - the framework binds OneKeyAutofillService by name from the
# manifest. Stripping or renaming the class breaks the bind. The activities
# are referenced from the manifest too so they're already kept by the AGP
# default rules; we only need an explicit rule for the service.
-keep class com.onekey.feature.autofill.service.OneKeyAutofillService { *; }

# Parcelable @Parcelize types crossing process boundaries. ParsedFields rides
# Intent extras between OneKeyAutofillService (our process) and
# AutofillUnlockActivity (also our process, but launched via PendingIntent so
# the OS handles the parcel). AutofillField is its nested member. R8's default
# Parcelable rules cover most cases, but @Parcelize-generated CREATORs
# occasionally lose their writeToParcel signature under aggressive shrinking
# - this keep prevents the regression entirely.
-keep class com.onekey.feature.autofill.domain.ParsedFields { *; }
-keep class com.onekey.feature.autofill.domain.AutofillField { *; }
-keep class com.onekey.feature.autofill.domain.AutofillField$* { *; }
-keep class com.onekey.feature.autofill.domain.AutofillScenario { *; }

# Note on what's intentionally absent:
#   CameraX, ML Kit (barcode + text recognition), OpenCSV, Hilt's generated
#   components, and the Compose runtime all ship their own consumer-rules.pro
#   inside their AARs. R8 applies those automatically. App-level
#   `-keep class <lib>.** { *; }` rules duplicate the library's intent and
#   prevent R8 from shrinking what the library author already marked safe.
#
#   Platform classes (javax.crypto.**, java.security.**, android.security.**)
#   live in the Android framework, not in the app DEX. R8 doesn't touch them
#   regardless of -keep rules - keeping them is a no-op.
