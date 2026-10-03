# Keep rules for the release build's R8 pass.
#
# Hilt, Room, DataStore, Navigation 3, kotlinx.serialization and OkHttp ship
# their own consumer rules, and the app uses no reflection of its own, so
# nothing needs keeping here yet.
