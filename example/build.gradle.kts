plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "eu.buney.interpose.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.buney.interpose.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // The whole point of the example: a runner that installs Interpose before the
        // app's Application.onCreate builds its OkHttpClient. A plain AndroidJUnitRunner
        // here would capture nothing from that client, however correct the test looked.
        testInstrumentationRunner = "eu.buney.interpose.example.ExampleInterposeRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // What the app ships with. Note what is *not* here: the app takes on nothing from
    // Interpose, and gains no WebView dependency either.
    implementation(libs.okhttp)

    // Test scope only, exactly as a consumer would take it.
    androidTestImplementation(project(":interpose"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
    // Interpose keeps androidx.webkit compileOnly so an app without a WebView carries
    // nothing; capturing WebView traffic means putting it on the test classpath, where
    // the instrumentation's dex joins the app's own classloader and the runtime lookup
    // in WebViewProxy finds it.
    androidTestImplementation(libs.androidx.webkit)
}
