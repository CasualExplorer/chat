# Keep rules for the release build's R8 pass.
#
# Hilt, Room, DataStore, Navigation 3, kotlinx.serialization and OkHttp ship
# their own consumer rules, and the app uses no reflection of its own.

# Tink (through security-crypto, used once to read the old encrypted keys)
# references Error Prone's compile-time-only annotations.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
