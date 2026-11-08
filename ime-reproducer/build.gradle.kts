plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val sharedTestSources = layout.buildDirectory.dir("generated/sharedTestSources")
val prepareSharedTestSources by tasks.registering(Sync::class) {
    from("../app/src/androidTest/java") {
        include("io/github/thiesi/chitchat/testing/NativeTap.kt",
            "io/github/thiesi/chitchat/testing/NativeInputWindow.kt",
            "io/github/thiesi/chitchat/testing/NativeImeServer.kt")
    }
    into(sharedTestSources)
}

android {
    namespace = "io.github.thiesi.chitchat.imereproducer"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.thiesi.chitchat.imereproducer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "diagnostic-1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("androidTest").java.srcDir(sharedTestSources)
    lint { warningsAsErrors = true }
}
kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn(prepareSharedTestSources) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
}
