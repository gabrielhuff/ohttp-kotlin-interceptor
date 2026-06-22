plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    // OkHttp is part of the public API (OhttpInterceptor implements
    // okhttp3.Interceptor), so it's exposed transitively.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // HPKE (RFC 9180) comes from BouncyCastle, whose public HPKEContext exposes
    // Seal/Open AND Export — the latter is what OHTTP response encryption
    // (RFC 9458 §4.5) needs and what kept us out of any library-internal APIs.
    // None of BouncyCastle's types leak through our public API, so it stays an
    // implementation detail.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
