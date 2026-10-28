plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("io.ktor.plugin") version "3.1.3"
}

group = "dev.nsiva.radar"
version = "0.1.0"

application { mainClass.set("dev.nsiva.radar.ApplicationKt") }

repositories { mavenCentral() }

val ktor = "3.1.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-encoding:$ktor")   // gzip (Stack Exchange always compresses)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.3.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }

ktor { fatJar { archiveFileName.set("idea-radar.jar") } }
