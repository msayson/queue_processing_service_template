plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("aws.sdk.kotlin:bom:1.3.92"))
    implementation("aws.sdk.kotlin:sqs")
    implementation("aws.sdk.kotlin:cloudwatch")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.template.queue.MainKt")
}

kotlin {
    jvmToolchain(25)
}
