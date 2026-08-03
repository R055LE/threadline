import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val applicationIdOverride = providers.gradleProperty("threadline.applicationId").orNull
val releaseApplicationId = providers.gradleProperty("threadline.releaseApplicationId").get()
val threadlineVersionCode = providers.gradleProperty("threadline.versionCode").get().toInt()
val threadlineVersionName = providers.gradleProperty("threadline.versionName").get()

android {
    namespace = "dev.threadline"
    compileSdk = 37

    defaultConfig {
        applicationId = applicationIdOverride ?: releaseApplicationId
        minSdk = 24
        targetSdk = 37
        versionCode = threadlineVersionCode
        versionName = threadlineVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (applicationIdOverride == null) {
                applicationIdSuffix = ".debug"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)

    implementation(libs.connectbot.sshlib)
    implementation(libs.connectbot.termlib)
    implementation(libs.conscrypt.android)

    // Silence library logging in the POC so hosts, usernames, and auth details
    // cannot accidentally enter Logcat through an installed SLF4J provider.
    runtimeOnly(libs.slf4j.nop)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
