// Android-safe: pure Kotlin/JVM, no AWT.
dependencies {
    api("com.google.zxing:core:3.5.3")                       // QR encoder + Reed-Solomon (Apache-2.0)
    implementation("io.github.andreypfau:raptorq-kotlin:1.0.0") // RaptorQ RFC 6330, pure Kotlin (Apache-2.0)
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")  // BLAKE2s for GhostPacket packet_id test only
}
