// The testing module reaches into both :core's BHTTP/OHTTP internals and
// :interceptor's OkHttp adapter (also internal) so the in-process gateway
// doesn't have to promote anything to the public API. -Xfriend-paths grants
// this access against both jars.
val core = project(":core")
val interceptor = project(":interceptor")

dependencies {
    api(interceptor)
    api(core)
    api("com.squareup.okhttp3:mockwebserver:4.12.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configure {
    val coreJar = core.tasks.named("jar")
    val interceptorJar = interceptor.tasks.named("jar")
    dependsOn(coreJar, interceptorJar)
    compilerOptions {
        freeCompilerArgs.add(
            coreJar.flatMap { (it as Jar).archiveFile }.map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
        freeCompilerArgs.add(
            interceptorJar.flatMap { (it as Jar).archiveFile }.map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
    }
}

// Build the two Rust reference binaries used by the interop tests:
//   - reference-gateway: martinthomson/ohttp (spec author's `ohttp` crate)
//   - reference-relay:   payjoin/ohttp-relay (closest pure-Rust relay binary)
//
// Both binaries are conditional on `cargo` being available on PATH; the
// corresponding tests are skipped automatically when their binaries are
// missing.
val referenceGatewayDir = file("${rootProject.projectDir}/interop/reference-gateway")
val referenceRelayDir = file("${rootProject.projectDir}/interop/reference-relay")

fun registerCargoBuild(name: String, dir: java.io.File, description: String) =
    tasks.register(name) {
        group = "verification"
        this.description = description
        doLast {
            try {
                val probe = ProcessBuilder("cargo", "--version").redirectErrorStream(true).start()
                if (probe.waitFor() != 0) {
                    logger.warn("`cargo` not available; the corresponding interop test will be skipped")
                    return@doLast
                }
            } catch (e: Exception) {
                logger.warn("`cargo` not available; the corresponding interop test will be skipped")
                return@doLast
            }
            val proc = ProcessBuilder("cargo", "build", "--release")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            if (rc != 0) throw GradleException("cargo build failed in $dir (rc=$rc):\n$out")
        }
    }

val buildReferenceGateway = registerCargoBuild(
    "buildReferenceGateway",
    referenceGatewayDir,
    "Builds the martinthomson/ohttp reference gateway used for interop tests",
)
val buildReferenceRelay = registerCargoBuild(
    "buildReferenceRelay",
    referenceRelayDir,
    "Builds the payjoin/ohttp-relay reference relay used for interop tests",
)

tasks.named("test").configure {
    dependsOn(buildReferenceGateway, buildReferenceRelay)
}
