plugins {
    kotlin("jvm") version "2.3.20"
    application
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("aws.sdk.kotlin:bom:1.6.56"))
    implementation("aws.sdk.kotlin:sqs:1.6.56")
    implementation("aws.sdk.kotlin:cloudwatch:1.6.56")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.mockito:mockito-core:5.23.0")

    // Testcontainers for local integration testing.
    // See: https://java.testcontainers.org/quickstart/junit_5_quickstart/
    testImplementation("org.testcontainers:localstack:1.21.4")
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
}

application {
    mainClass.set("com.template.queue.MainKt")
}

kotlin {
    jvmToolchain(25)
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.test {
    useJUnitPlatform {
        excludeTags("localIntegTest")
    }

    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

val localIntegTest = tasks.register<Test>("localIntegTest") {
    description = "Runs local integration tests."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("localIntegTest")
    }

    shouldRunAfter(tasks.test)

    onlyIf {
        System.getProperty("runLocalIntegTests") == "true"
    }

    // Disable JaCoCo for integ tests
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class) {
        isEnabled = false
    }
}
