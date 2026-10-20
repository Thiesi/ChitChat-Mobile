plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.thiesi.chitchat"
    compileSdk = 36

    defaultConfig {
        // Development identity; confirm the public application ID before release signing/DAL.
        applicationId = "io.github.thiesi.chitchat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { warningsAsErrors = true }
}

kotlin { jvmToolchain(17) }

// Live tests are opt-in and never run against private credentials in ordinary CI.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name != "liveSessionTest") exclude("**/LiveSessionTest.class")
}
tasks.register<org.gradle.api.tasks.testing.Test>("liveSessionTest") {
    group = "verification"
    description = "Explicit live password/session test using ignored .env.integration (never prints credentials)."
    val unitTests = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
    testClassesDirs = unitTests.get().testClassesDirs
    classpath = unitTests.get().classpath
    include("**/LiveSessionTest.class")
    systemProperty("chitchat.liveConfig", rootProject.file(".env.integration").absolutePath)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
