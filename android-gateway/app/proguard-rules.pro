# Keep model classes for Gson
-keepclassmembers class com.smsbridge.gateway.protocol.** { *; }
-keepclassmembers class com.smsbridge.gateway.data.** { *; }
# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Keep Java-WebSocket
-keep class org.java_websocket.** { *; }
