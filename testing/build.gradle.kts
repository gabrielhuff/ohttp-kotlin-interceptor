// The testing module reaches into the interceptor's `internal` APIs (BHTTP +
// OHTTP gateway routines) so that the in-process gateway and relay don't have
// to be promoted to the public API surface. -Xfriend-paths grants this access.
val interceptor = project(":interceptor")

dependencies {
    api(interceptor)
    api("com.squareup.okhttp3:mockwebserver:4.12.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configure {
    val interceptorJar = interceptor.tasks.named("jar")
    dependsOn(interceptorJar)
    compilerOptions {
        freeCompilerArgs.add(
            interceptorJar.flatMap { (it as Jar).archiveFile }.map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
    }
}

// Build the Go reference gateway used by ReferenceGatewayInteropTest. We don't
// fail the test task if Go isn't available; the test is conditional.
val referenceGatewayDir = file("${rootProject.projectDir}/interop/reference-gateway")
val referenceGatewayBinary = file("${referenceGatewayDir}/ohttp-reference-gateway")

val buildReferenceGateway = tasks.register("buildReferenceGateway") {
    group = "verification"
    description = "Builds the chris-wood/ohttp-go reference gateway used for interop tests"
    doLast {
        try {
            val probe = ProcessBuilder("go", "version").redirectErrorStream(true).start()
            if (probe.waitFor() != 0) {
                logger.warn("`go` not available; ReferenceGatewayInteropTest will be skipped")
                return@doLast
            }
        } catch (e: Exception) {
            logger.warn("`go` not available; ReferenceGatewayInteropTest will be skipped")
            return@doLast
        }
        val proc = ProcessBuilder("go", "build", "-o", "ohttp-reference-gateway", "./")
            .directory(referenceGatewayDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        if (rc != 0) throw GradleException("go build failed (rc=$rc):\n$out")
    }
}

tasks.named("test").configure {
    dependsOn(buildReferenceGateway)
}
