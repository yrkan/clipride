# Nordic BLE
-keep class no.nordicsemi.** { *; }
-dontwarn no.nordicsemi.**

# Protobuf Lite
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Karoo Extension SDK
-keep class io.hammerhead.karooext.** { *; }
-dontwarn io.hammerhead.karooext.**

# ClipRide extension service and receiver
-keep class com.clipride.karoo.ClipRideExtension { *; }
-keep class com.clipride.karoo.ClipRideActionReceiver { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Glance
-keep class androidx.glance.** { *; }
