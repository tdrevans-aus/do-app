import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
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

// tools:node is validated before manifest placeholders are substituted, so the
// mode switch is a generated manifest merged into every variant.
abstract class GenerateExactAlarmManifestTask : DefaultTask() {
    @get:Input
    abstract val mode: Property<String>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val xml = if (mode.get() == "SCHEDULE") {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
            </manifest>
            """.trimIndent()
        } else {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
                <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
            </manifest>
            """.trimIndent()
        }
        val output = manifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(xml + "\n")
    }
}

val generateExactAlarmManifest = tasks.register<GenerateExactAlarmManifestTask>("generateExactAlarmManifest") {
    mode.set(exactAlarmMode)
    manifestFile.set(layout.buildDirectory.file("generated/exactAlarm/AndroidManifest.xml"))
}

extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.manifests.addGeneratedManifestFile(generateExactAlarmManifest) { it.manifestFile }
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

        for ((variant, text) in listOf("release" to release, "debug" to debug)) {
            val permissions = usesPermissions(text)
            val useExact = permissions.filter { it.name == "android.permission.USE_EXACT_ALARM" }
            val scheduleExact = permissions.filter { it.name == "android.permission.SCHEDULE_EXACT_ALARM" }
            if (mode == "SCHEDULE") {
                check(useExact.isEmpty()) {
                    "$variant SCHEDULE manifest must not declare USE_EXACT_ALARM: $useExact"
                }
                check(scheduleExact.size == 1 && scheduleExact.single().maxSdk == null) {
                    "$variant SCHEDULE manifest must declare SCHEDULE_EXACT_ALARM with no maxSdkVersion: $scheduleExact"
                }
            } else {
                check(useExact.size == 1 && useExact.single().maxSdk == null) {
                    "$variant USE manifest must declare USE_EXACT_ALARM with no maxSdkVersion: $useExact"
                }
                check(scheduleExact.size == 1 && scheduleExact.single().maxSdk == "32") {
                    "$variant USE manifest must declare SCHEDULE_EXACT_ALARM only with maxSdkVersion=32: $scheduleExact"
                }
            }
        }
    }
}

private data class ManifestPermission(val name: String, val maxSdk: String?)

private fun usesPermissions(xml: String): List<ManifestPermission> {
    val tags = Regex("""<uses-permission\b([^>]*)/?>""").findAll(xml)
    return tags.map { match ->
        val attrs = match.groupValues[1]
        val name = Regex("""android:name="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: error("uses-permission is missing android:name: $attrs")
        val maxSdk = Regex("""android:maxSdkVersion="([^"]+)"""").find(attrs)?.groupValues?.get(1)
        ManifestPermission(name, maxSdk)
    }.toList()
}

tasks.matching {
    it.name == "assembleDebug" || it.name == "lintDebug" || it.name == "testDebugUnitTest"
}.configureEach {
    dependsOn(checkAlarmManifests)
}
