import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile

// === Cronet API resolution ===
// The org.chromium.net.* classes come from `cronet-api`, resolved as a normal
// Maven dependency from Maven Central — nothing is vendored in the repo.
//
// The wrinkle: every published `cronet-api` (Google's `org.chromium.net:cronet-api`
// and the `com.androidacy:cronet-api` mirror alike) ships as an Android `.aar`, and
// this module is intentionally a plain `kotlin("jvm")` library with no Android
// Gradle Plugin — so Gradle can't consume the `.aar` variant directly. We register
// an artifact transform that unpacks the jar(s) carried inside the `.aar`
// (`classes.jar` and any `libs/*.jar`). This is the same mechanism AGP uses
// internally; it's cacheable and lazy, so the rest of the build just sees a jar.
//
// The API is wired `compileOnly`, so it never lands in our published jar — we don't
// want to claim ownership of org.chromium.net classes; at runtime consumers supply
// their own Cronet implementation (cronet-embedded, Play Services, HttpEngine, …).
//
// TODO: once Google's Maven repo (https://maven.google.com/) is reachable from the
// build, point the coordinate below at `org.chromium.net:cronet-api:<version>` — the
// transform keeps working since that artifact is an `.aar` too.

@CacheableTransform
abstract class ExtractAarJars : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputAar: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val aar = inputAar.get().asFile
        ZipFile(aar).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.size > 0L && entry.name.endsWith(".jar") &&
                        (entry.name == "classes.jar" || entry.name.startsWith("libs/"))
                }
                .forEach { entry ->
                    val out = outputs.file(entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
        }
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)
val cronetClasses = "cronet-api-classes"

dependencies {
    registerTransform(ExtractAarJars::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, cronetClasses)
    }
}

// Resolvable configuration that holds the cronet-api `.aar`. `@aar` fetches the
// artifact directly (no variant matching), and the artifactView below runs the
// transform to hand back the unpacked jar(s).
val cronetApiAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val cronetApi = cronetApiAar.incoming.artifactView {
    attributes.attribute(artifactType, cronetClasses)
}.files

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
