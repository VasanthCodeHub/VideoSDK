# MeshCall SDK consumer ProGuard / R8 rules.
# These ship inside the AAR and are merged into the consuming app's release build.

# ---- Public API surface (host apps may reflectively call these) ----
-keep class dev.meshcall.sdk.api.** { *; }

# ---- org.webrtc native bridge ----
# The WebRTC library JNI classes must not be renamed/obfuscated; method ids are
# resolved against these names via the peer-native layer.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepclasseswithmembernames class org.webrtc.** {
    native <methods>;
}

# --- Gson members used by the Socket.IO client in the AAR ----
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# Keep Java-bean class members of the internal schema DTOs (they are only ever
# serialized, never reflectively accessed, but keeping them future-proofs R8).
-keep class dev.meshcall.sdk.internal.signaling.** { *; }

# --- socket.io-client dependency ----
# The Socket.IO client uses reflection via OkHttp's readReflectively and event
# transport; keep the transport classes.
-keep class io.socket.** { *; }
-keep class com.corundumstudio.socketio.** { *; }
-dontwarn io.socket.**
-dontwarn com.corundumstudio.socketio.**

# --- okhttp (transitive dependency of socket.io) ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- kotlinx.serialization / coroutines (if enabled) ----
-dontwarn org.jetbrains.annotations.**

# R8 full mode: keep the JNI glue for WebRTC even under shrinking.
-if class org.webrtc.PeerConnectionFactory
-keep,allowshrinking class org.webrtc.PeerConnectionFactory {
    native <methods>;
}
