// :server — a local gRPC server for the RideNow demo.
// Run:  ./gradlew :server:run          (listens on 0.0.0.0:50051)
//       ./gradlew :server:run --args="--port 6000 --chaos 0.3"
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.ridenow.server.MainKt")
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.grpc.netty.shaded) // HTTP/2 server transport

    testImplementation(libs.junit)
    testImplementation(libs.grpc.inprocess) // in-memory transport for fast tests
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in` // so Ctrl+C triggers a graceful shutdown
}
