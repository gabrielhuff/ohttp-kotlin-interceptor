// Cronet API stubs live in a dedicated source set so they don't end up in the
// published jar (we don't want to claim ownership of org.chromium.net classes).
// At runtime, consumers depend on the real `org.chromium.net:cronet-api` from
// Google's Maven repo (https://maven.google.com/), which provides identical
// abstract class shapes. Maven Central does not host cronet-api, which is why
// we vendor stubs for local compilation here.
val cronetApiStub = sourceSets.create("cronetApiStub") {
    java.srcDir("src/cronetApiStub/java")
}

val cronetApiStubClasspath = files(cronetApiStub.output.classesDirs)

val interceptor = project(":interceptor")

dependencies {
    api(interceptor)
    api("com.squareup.okhttp3:okhttp:4.12.0")

    compileOnly(cronetApiStubClasspath)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(project(":testing"))
    // Tests use the same stub surface as compile (they aren't real Cronet,
    // but we exercise the abstract API end-to-end via a FakeCronetEngine).
    testImplementation(cronetApiStubClasspath)
    testRuntimeOnly(cronetApiStubClasspath)
}

// Same reasoning as ':testing': reach into ':interceptor' internals (BHTTP +
// OHTTP) to avoid promoting them to public API.
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configure {
    val interceptorJar = interceptor.tasks.named("jar")
    dependsOn(interceptorJar)
    compilerOptions {
        freeCompilerArgs.add(
            interceptorJar.flatMap { (it as Jar).archiveFile }.map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
    }
}
