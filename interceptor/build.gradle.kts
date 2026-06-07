val core = project(":core")

dependencies {
    api(core)
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Friend access to :core's internals so the OkHttp adapter can speak
// directly to Bhttp / BhttpRequest / BhttpResponse / Ohttp without us having
// to promote them to public API.
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configure {
    val coreJar = core.tasks.named("jar")
    dependsOn(coreJar)
    compilerOptions {
        freeCompilerArgs.add(
            coreJar.flatMap { (it as Jar).archiveFile }.map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
    }
}
