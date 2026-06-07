// The Cronet API is compiled against `org.chromium.net:cronet-api`. That artifact
// is only published on Google's Maven repo (https://maven.google.com/) and is not
// on Maven Central; until this project can resolve it from there, we vendor the
// real API jar locally under libs/. It's wired as `compileOnly` so it never ends
// up in our published jar — we don't want to claim ownership of org.chromium.net
// classes; at runtime consumers supply their own Cronet implementation.
//
// TODO: replace this file dependency with the Maven coordinate once Google's
// Maven repo is reachable from the build:
//     compileOnly("org.chromium.net:cronet-api:<version>")
val cronetApi = files("libs/cronet-api-112.0.5615.62.jar")

val core = project(":core")

dependencies {
    // No OkHttp on the production classpath — :core gives us neutral BHTTP /
    // OHTTP types so this module stays "Cronet, not OkHttp".
    api(core)

    compileOnly(cronetApi)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(project(":testing"))
    // Tests exercise the real Cronet API end-to-end via a FakeCronetEngine that
    // subclasses the genuine abstract classes from the vendored jar.
    testImplementation(cronetApi)
    testRuntimeOnly(cronetApi)
}

// Friend access to :core's internals (BhttpRequest, Bhttp, Ohttp).
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configure {
    val coreJar = core.tasks.named("jar")
    dependsOn(coreJar)
    compilerOptions {
        freeCompilerArgs.add(
            coreJar.flatMap { (it as Jar).archiveFile }.map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
    }
}
