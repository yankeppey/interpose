import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.gradle.language.base.plugins.LifecycleBasePlugin

// Binary-compatibility (ABI) validation for an AGP 9 built-in-Kotlin Android library.
//
// Interpose is written for a test classpath it does not control: it resolves against
// whatever OkHttp the app under test already uses, and its API ends up in assertions
// spread across somebody else's suite. A dump that has to be regenerated on purpose is
// what makes "we did not mean to change that" visible in review rather than in a
// consumer's red build.
//
// The upstream binary-compatibility-validator plugin cannot do this itself: it registers
// apiDump/apiCheck from the kotlin-android plugin's callback, which AGP 9 built-in Kotlin
// never applies, so applying BCV is a no-op. We reuse its task types against the release
// compile output instead. Drop this convention once BCV (or KGP's own abiValidation)
// supports AGP-9 built-in-Kotlin Android libraries.
// https://github.com/Kotlin/binary-compatibility-validator/issues/312
//
// There is no second dump taken from post-R8 output, as a library that ships minified
// would want: Interpose publishes an unminified AAR, so the classes in this dump are the
// classes that ship.

val apiFileName = "$name.api"
val apiSubdir = "api"
val goldenApiFile = layout.projectDirectory.file("$apiSubdir/$apiFileName")

// KotlinApiBuildTask reads bytecode at execution time and needs ASM plus a metadata
// parser on a runtime classpath. BCV normally supplies these from the kotlin-android
// callback that never fires here, so we hand them over directly. kotlin-metadata-jvm only
// has to be new enough to read what we emit — the language version is pinned to 2.2 in
// the library's build file, and this reader is 2.3.
val bcvRuntimeClasspath = configurations.detachedConfiguration(
    dependencies.create("org.ow2.asm:asm:9.9.1"),
    dependencies.create("org.ow2.asm:asm-tree:9.9.1"),
    dependencies.create("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.20"),
)

// AGP registers the variant compile tasks during evaluation, so wire them afterwards.
afterEvaluate {
    val apiBuild = tasks.register<KotlinApiBuildTask>("apiBuild") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Generates the public ABI dump from the release compilation of $name."
        // Wired to the compile tasks' OUTPUTS rather than hardcoded intermediates/ paths,
        // so an AGP layout change cannot silently feed us an empty dump that passes.
        inputClassesDirs.from(tasks.named("compileReleaseKotlin").map { it.outputs.files })
        inputClassesDirs.from(tasks.named("compileReleaseJavaWithJavac").map { it.outputs.files })
        outputApiFile.set(layout.buildDirectory.file("$apiSubdir/$apiFileName"))
        runtimeClasspath.from(bcvRuntimeClasspath)
    }

    val apiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Checks the public ABI of $name against the committed $apiSubdir/$apiFileName."
        projectApiFile.set(goldenApiFile)
        generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
    }

    tasks.register("apiDump") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Regenerates the committed $apiSubdir/$apiFileName golden ABI dump for $name."
        dependsOn(apiBuild)
        val generated = apiBuild.flatMap { it.outputApiFile }
        val golden = goldenApiFile.asFile
        inputs.files(generated)
        outputs.file(golden)
        doLast {
            val source = generated.get().asFile
            if (source.exists()) {
                golden.parentFile.mkdirs()
                source.copyTo(golden, overwrite = true)
            } else {
                golden.delete()
            }
        }
    }

    tasks.named("check") { dependsOn(apiCheck) }
}
