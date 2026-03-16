plugins {
    kotlin("jvm") version "2.1.10"
    application
}

group = "dev.k8.pgmanager"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.fabric8:kubernetes-client:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.k8.pgmanager.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
