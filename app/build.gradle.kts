import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val exactAlarmMode = providers.gradleProperty("exactAlarmMode").orElse("USE").get()
require(exactAlarmMode == "USE" || exactAlarmMode == "SCHEDULE") {
    "exactAlarmMode must be USE or SCHEDULE (was '$exactAlarmMode')"
}

android {
    namespace = "com.evansappstudio.doapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evansappstudio.doapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["exactAlarmPermission"] = if (exactAlarmMode == "SCHEDULE") {
            "android.permission.SCHEDULE_EXACT_ALARM"
        } else {
            "android.permission.USE_EXACT_ALARM"
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

val checkAlarmManifests = tasks.register("checkAlarmManifests") {
    group = "verification"
    description = "Release manifest has no debug receiver, and the exact-alarm permission matches exactAlarmMode."
    dependsOn("processReleaseMainManifest", "processDebugMainManifest")
    val buildDirectory = layout.buildDirectory
    val mode = exactAlarmMode
    doLast {
        val root = buildDirectory.get().asFile
        fun mergedManifest(variant: String): String {
            val taskName = "process${variant.replaceFirstChar { it.uppercase() }}MainManifest"
            val files = root.walkTopDown().filter { file ->
                file.isFile &&
                    file.name == "AndroidManifest.xml" &&
                    file.path.contains(taskName)
            }.toList()
            check(files.isNotEmpty()) {
                "No $taskName manifest under $root"
            }
            return files.joinToString("\n") { it.readText() }
        }

        val release = mergedManifest("release")
        val debug = mergedManifest("debug")
        check(!release.contains("DebugReceiver")) {
            "Release manifest contains the debug receiver"
        }
        check(!release.contains("com.evansappstudio.doapp.debug")) {
            "Release manifest contains the debug package"
        }
        check(debug.contains("com.evansappstudio.doapp.debug.DebugReceiver")) {
            "Debug manifest is missing DebugReceiver"
        }

        val expected = if (mode == "SCHEDULE") {
            "android.permission.SCHEDULE_EXACT_ALARM"
        } else {
            "android.permission.USE_EXACT_ALARM"
        }
        val forbidden = if (mode == "SCHEDULE") {
            "android.permission.USE_EXACT_ALARM"
        } else {
            "android.permission.SCHEDULE_EXACT_ALARM"
        }
        for ((variant, text) in listOf("release" to release, "debug" to debug)) {
            check(text.contains(expected)) { "$variant manifest is missing $expected" }
            check(!text.contains(forbidden)) { "$variant manifest must not contain $forbidden" }
        }
    }
}

tasks.matching {
    it.name == "assembleDebug" || it.name == "lintDebug" || it.name == "testDebugUnitTest"
}.configureEach {
    dependsOn(checkAlarmManifests)
}
