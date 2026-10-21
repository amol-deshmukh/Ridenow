// :proto — the single source of truth for the API contract.
//
// Both :server and :app depend on this module, so client and server can never
// drift apart: change the .proto, rebuild, and the compiler tells you who broke.
//
// We generate the *lite* runtime flavour for both sides. Lite is what Android
// should use (smaller, no reflection/descriptors → R8 friendly). The server can
// run on lite too, which keeps this demo to a single generated artifact.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `java-library`
}

java {
    // Java 17: required by grpc-kotlin-stub 1.5+, and matches the app's compileOptions.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // `api` so consumers (app/server) see the generated types' dependencies.
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf.lite)
    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
}

protobuf {
    // protoc = the Protobuf compiler. Downloaded as a Maven artifact.
    protoc { artifact = libs.protobuf.protoc.get().toString() }

    plugins {
        // Generates RideServiceGrpc (Java stubs: blocking / future / async).
        create("grpc") { artifact = libs.grpc.protoc.gen.java.get().toString() }
        // Generates RideServiceGrpcKt (suspend funs + Flow stubs).
        create("grpckt") { artifact = "${libs.grpc.protoc.gen.kotlin.get()}:jdk8@jar" }
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") { option("lite") }   // message classes (lite)
                create("kotlin") { option("lite") } // Kotlin DSL builders: fareEstimateRequest { ... }
            }
            task.plugins {
                create("grpc") { option("lite") }
                create("grpckt") { option("lite") }
            }
        }
    }
}
