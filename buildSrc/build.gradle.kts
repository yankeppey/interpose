plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    // Not applied as a plugin: BCV registers its apiDump/apiCheck tasks from the
    // kotlin-android plugin's callback, and AGP 9's built-in Kotlin never applies that
    // plugin, so applying BCV here would be a silent no-op. The `interpose.abi-validation`
    // convention reuses its task classes (kotlinx.validation.*) and wires them by hand
    // instead; this pin must stay in step with the runtime classpath that convention
    // builds. https://github.com/Kotlin/binary-compatibility-validator/issues/312
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1")
}
