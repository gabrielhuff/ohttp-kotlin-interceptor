// The Cronet API classes (org.chromium.net.*) come from `cronet-api`, resolved as
// a normal Maven dependency from Maven Central — nothing is vendored in the repo.
//
// The catch: every published `cronet-api` (Google's `org.chromium.net:cronet-api`
// and the `com.androidacy:cronet-api` mirror) is packaged as an Android `.aar`,
// and this module is intentionally a plain `kotlin("jvm")` library with no Android
// Gradle Plugin — so Gradle can't consume the `.aar` variant directly. We therefore
// fetch the `.aar` artifact only (the `@aar` notation skips variant matching) and
// unpack the real API jar it carries at `libs/cronet_api.jar`.
//
// The API is wired `compileOnly` so it never lands in our published jar — we don't
// want to claim ownership of org.chromium.net classes; at runtime consumers supply
// their own Cronet implementation (cronet-embedded, Play Services, HttpEngine, …).
//
// TODO: once Google's Maven repo (https://maven.google.com/) is reachable from the
// build, this can collapse to `compileOnly("org.chromium.net:cronet-api:<version>")`
// (still `@aar`-unpacked here unless/until a jar variant is offered).
val cronetApiAar: Configuration by configurations.creating
val extractCronetApi = tasks.register<Copy>("extractCronetApi") {
    from(provider { zipTree(cronetApiAar.singleFile) }) {
        include("libs/cronet_api.jar")
        eachFile { relativePath = RelativePath(true, "cronet-api.jar") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("cronet-api"))
}
val cronetApi = files(extractCronetApi.map { it.destinationDir.resolve("cronet-api.jar") })

val core = project(":core")

dependencies {
    cronetApiAar("com.androidacy:cronet-api:112.0.5615.62@aar")

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
    // subclasses the genuine abstract classes from the resolved jar.
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
