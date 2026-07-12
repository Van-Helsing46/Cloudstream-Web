import java.util.concurrent.TimeUnit

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("io.ktor.plugin") version "3.0.3"
    application
}

group = "com.cloudstreamweb"
version = "0.0.1"

application {
    mainClass.set("com.cloudstreamweb.ApplicationKt")
}

repositories {
    mavenCentral()
    google()
    // Cloudstream JVM library + NiceHttp (extension runtime)
    maven("https://jitpack.io")
}

dependencies {
    // ---- Ktor server (Netty) ----
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.ktor:ktor-server-call-id:$ktor_version")
    implementation("io.ktor:ktor-server-swagger:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml:$ktor_version")

    // ---- Ktor client (scraping / proxy towards the sources) ----
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-okhttp:$ktor_version")

    // ---- HTML parsing (as Cloudstream providers do) ----
    implementation("org.jsoup:jsoup:1.22.2")

    // ---- Logging (structured JSON) ----
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    runtimeOnly("org.codehaus.janino:janino:3.1.12") // <if> conditions in logback.xml

    // ---- Extension runtime ----
    // Cloudstream library compiled for the JVM: provides MainAPI and the runtime graph.
    // Pinned to a fixed commit SHA (not master-SNAPSHOT): the SNAPSHOT forces Gradle to
    // re-contact JitPack on every resolution, and a JitPack outage/timeout there took the
    // whole backend down (compileKotlin failing before the server could even start).
    implementation("com.github.recloudstream.cloudstream:library-jvm:11792dd65c")
    // DEX→JAR conversion for dynamically-loaded (.cs3) extensions (Strada A).
    // R8-based (Google's real DEX toolchain, run in reverse): preserves Kotlin inline-class
    // synthetic method names (e.g. Result."constructor-impl") that dex2jar forks mangle.
    // The r8 classes are bundled inside the dex-translator jar; its POM points at
    // com.android.tools:r8 (not on Maven Central), so exclude it and use the bundled copy.
    implementation("software.coley:dex-translator:1.1.1") {
        exclude(group = "com.android.tools", module = "r8")
    }
    // r8's runtime dependency, pulled transitively via r8 before it was excluded above.
    implementation("com.google.code.gson:gson:2.10.1")
    // Embedded Kotlin compiler: recompile extensions from source (Strada B automated) for maximum
    // coverage — on the classpath so the out-of-process compiler is available to the runtime.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
    // dex-translator uses these ASM modules at runtime but its POM only declares asm-core.
    // asm-util pulls asm-tree + asm-analysis; asm-commons pulls asm-tree — together they cover them.
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation("org.ow2.asm:asm-util:9.5")
    // The lib keeps these as `implementation` (not `api`): recompiled extensions
    // reference them at compile time, so they must be exposed by our classpath.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.13.1")
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    // org.json: on Android it ships with the platform; on the JVM the artifact is needed
    implementation("org.json:json:20250107")

    // ---- Test ----
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

kotlin {
    jvmToolchain(21)
}

// Belt-and-suspenders for any other changing/SNAPSHOT module on the classpath: don't let a
// flaky remote (e.g. JitPack) block the build once a version has already been resolved once.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(30, TimeUnit.DAYS)
}

// The embedded Kotlin compiler pushes the fat jar past 65535 entries → needs the zip64 extension.
// ShadowJar extends Zip, so set it without importing the shadow plugin type.
tasks.named("shadowJar") {
    (this as org.gradle.api.tasks.bundling.Zip).isZip64 = true
}
