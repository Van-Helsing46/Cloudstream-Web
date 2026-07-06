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
    implementation("com.github.recloudstream.cloudstream:library-jvm:master-SNAPSHOT")
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
