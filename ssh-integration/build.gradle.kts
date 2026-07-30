import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        kotlin {
            // Compile the exact production adapter and its domain types in a
            // plain JVM process. Android local tests put stub android.* classes
            // on the runtime classpath, which makes sshlib select Android's
            // otherwise-unimplemented Base64 path.
            srcDir("../app/src/main/java")
            include("dev/threadline/core/model/SessionModels.kt")
            include("dev/threadline/core/shell/BashShellIntegration.kt")
            include("dev/threadline/core/shell/ThreadlineOscParser.kt")
            include("dev/threadline/core/ssh/SshClientAdapter.kt")
            include("dev/threadline/core/ssh/ConnectBotSshClientAdapter.kt")
        }
    }
}

dependencies {
    implementation(libs.connectbot.sshlib)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // Results depend on disposable external state and must never enter the
    // Gradle build cache. test-adapter.sh also forces a live rerun.
    outputs.doNotCacheIf("SSH fixture tests depend on a live server") { true }
}
