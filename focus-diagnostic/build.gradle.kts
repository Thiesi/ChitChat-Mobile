plugins {
    id("com.android.test") // Same AGP already supplied by the root application plugin.
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
    from("../app/src/test/java") { include("io/github/thiesi/chitchat/testing/focus/FocusMetadata.kt") }
    into(sharedTestSources)
}

android {
    namespace = "io.github.thiesi.chitchat.diagnostic.focus"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Compile the original helpers verbatim into this test-only APK, never into production.
    sourceSets.getByName("main").java.srcDir(sharedTestSources)
}
kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn(prepareSharedTestSources) }

// The target process already loads these libraries. Match :app's resolved versions, as its
// ordinary androidTest classpath does, instead of introducing newer transitive test copies.
evaluationDependsOn(":app")
configurations.matching { it.name == "debugCompileClasspath" || it.name == "debugRuntimeClasspath" }.configureEach {
    shouldResolveConsistentlyWith(project(":app").configurations.getByName("debugRuntimeClasspath"))
}
// This configuration supplies only the installed target APK, not its transitive library variants.
configurations.matching { it.name.endsWith("TestedApks") }.configureEach { isTransitive = false }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.test)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.test.ext)
    implementation(libs.espresso.core)
}
