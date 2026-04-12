plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.4")
}

application {
    mainClass.set("com.template.queue.MainKt")
}

kotlin {
    jvmToolchain(25)
}
