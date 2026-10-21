import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
    id("interpose.abi-validation")
}

group = "eu.buney.interpose"
version = libs.versions.interpose.get()

android {
    namespace = "eu.buney.interpose"
    compileSdk = 36

    defaultConfig {
        // Verified down to 24. Below API 29 server-side ALPN goes through the
        // platform's own TLS stack, which is why that floor is where it is.
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// There is no Kotlin plugin here: AGP 9's built-in Kotlin supplies the compiler, and its
// default language version moves when AGP moves. For a published library that default is
// not ours to inherit silently — the language version decides the `kotlin.Metadata`
// version written into every class, and metadata from a newer Kotlin than the consumer's
// own compiler is a hard error on their side, not a warning. Interpose lands on the
// `androidTest` classpath of apps whose Kotlin we do not pick, so the floor stays where
// it is until we move it deliberately, in a release that says so.
//
// 2.2 is what AGP 9.4.1 emits today, so this pin changes nothing now; it only means the
// next AGP bump shows up as a failing ABI check here instead of a metadata error in
// somebody else's build.
kotlin {
    // Every declaration states its visibility and its return type, or the build fails.
    // The ABI dump is only as honest as the public surface it reads, and `public` by
    // default is how a helper meant to be internal ends up in it.
    explicitApi()

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    // `api`, not `implementation`: CapturedExchange exposes okhttp3.Headers and the
    // config DSL hands out an OkHttpClient.Builder, so a consumer writing assertions
    // needs these types on its own compile classpath.
    api(libs.okhttp)
    // Signs a leaf certificate per intercepted host. Neither the JDK nor Android has a
    // public API for minting an X.509 certificate, so this is doing real work.
    api(libs.okhttp.tls)

    // compileOnly, both: Interpose should impose no version on a consumer.
    //
    // Any project running instrumented tests already has the test runner, which is all
    // InterposeRunner needs. androidx.webkit is only touched when capturing WebView
    // traffic, and WebViewProxy checks for it at runtime before loading anything typed —
    // so an app with no WebView carries no extra dependency and loses nothing else.
    compileOnly(libs.androidx.test.runner)
    compileOnly(libs.androidx.webkit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.webkit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
    // An h2-speaking origin, so "it stays h2" is assertable without a live host.
    androidTestImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    // An empty javadoc jar, because Central refuses a deployment without one.
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        )
    )
    publishToMavenCentral()
    // Guarded so `publishToMavenLocal` works on a machine with no signing key; a real
    // release sets signingInMemoryKey and gets signatures.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "interpose-android", version.toString())

    pom {
        name = "Interpose"
        description =
            "In-process HTTP capture for Android instrumented tests. Runs a TLS-terminating " +
                "proxy inside the app under test, so a test can read every request the app " +
                "makes — including from the WebView and from code it does not own."
        inceptionYear = "2026"
        url = "https://github.com/yankeppey/interpose"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "yankeppey"
                name = "Andrei Buneyeu"
                url = "https://buney.eu"
            }
        }
        scm {
            url = "https://github.com/yankeppey/interpose/"
            connection = "scm:git:git://github.com/yankeppey/interpose.git"
            developerConnection = "scm:git:ssh://git@github.com/yankeppey/interpose.git"
        }
    }
}
