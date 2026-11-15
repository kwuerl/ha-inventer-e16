# BouncyCastle provides the TLS-PSK stack (TLS_PSK_WITH_AES_128_CBC_SHA) used to talk to the
# Easy Connect e16. It is used through the lightweight BcTlsCrypto API, but BC internals load a
# few services reflectively, so keep the library whole rather than risk a stripped class.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
