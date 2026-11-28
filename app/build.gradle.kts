import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

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
        buildConfigField("String", "EXACT_ALARM_MODE", "\"$exactAlarmMode\"")
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
        buildConfig = true
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // QA: Robolectric renders the Spec 11 Compose screens on the JVM.
        unitTests.isIncludeAndroidResources = true
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
    implementation(libs.androidx.lifecycle.process)
    releaseImplementation(libs.androidx.billing)
    releaseImplementation(libs.androidx.billing.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation("org.json:json:20240303")
    // QA (Spec 11): render Compose screens on the JVM, test only.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

val checkAlarmManifests = tasks.register("checkAlarmManifests") {
    group = "verification"
    description = "Release manifest has no debug receiver, exact-alarm permission matches exactAlarmMode, and no variant requests a battery-optimisation exemption. Release classes have no fake billing."
    dependsOn(
        "processReleaseMainManifest",
        "processDebugMainManifest",
        "compileReleaseKotlin",
        "compileDebugKotlin",
    )
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
        check(!release.contains("FAKE_PURCHASE")) {
            "Release manifest contains FAKE_PURCHASE"
        }
        check(!release.contains("FakeSubscriptionBilling")) {
            "Release manifest contains FakeSubscriptionBilling"
        }
        check(!release.contains("SET_QUIET_HOURS")) {
            "Release manifest contains SET_QUIET_HOURS"
        }
        check(!release.contains("DUMP_QUIET")) {
            "Release manifest contains DUMP_QUIET"
        }
        check(!release.contains("DndDebugOverride")) {
            "Release manifest contains DndDebugOverride"
        }
        check(!release.contains("INTERRUPTION_FILTER_CHANGED")) {
            "Release manifest contains an interruption-filter receiver"
        }
        check(!debug.contains("INTERRUPTION_FILTER_CHANGED")) {
            "Debug manifest contains an interruption-filter receiver"
        }
        val policy = Regex("ACCESS_NOTIFICATION_POLICY").findAll(release).count()
        check(policy == 1) {
            "Release manifest ACCESS_NOTIFICATION_POLICY count is $policy; this PR must not add another"
        }
        check(debug.contains("SET_QUIET_HOURS") && debug.contains("DUMP_QUIET")) {
            "Debug manifest is missing SET_QUIET_HOURS or DUMP_QUIET"
        }
        check(debug.contains("com.evansappstudio.doapp.debug.DebugReceiver")) {
            "Debug manifest is missing DebugReceiver"
        }
        check(debug.contains("FAKE_PURCHASE")) {
            "Debug manifest is missing FAKE_PURCHASE"
        }
        assertReleaseHasNoFakeBilling(root)
        assertReleaseHasNoQuietDebug(root)

        for ((variant, text) in listOf("release" to release, "debug" to debug)) {
            check(!text.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
                "$variant manifest contains REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
            }
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

private fun assertReleaseHasNoQuietDebug(root: java.io.File) {
    val needles = listOf("SET_QUIET_HOURS", "DUMP_QUIET", "DndDebugOverride")
    val releaseFiles = root.walkTopDown().filter { file ->
        file.isFile &&
            file.path.replace('\\', '/').contains("/release/") &&
            !file.path.replace('\\', '/').contains("/debug/") &&
            file.extension in setOf("class", "jar", "dex", "apk")
    }.toList()
    check(releaseFiles.isNotEmpty()) { "No release class output under $root" }
    for (needle in needles) {
        val hit = releaseFiles.firstOrNull { file -> fileContainsNeedle(file, needle) }
        check(hit == null) { "Release output contains $needle in $hit" }
    }
    val debugHasOverride = root.walkTopDown().any { file ->
        file.isFile &&
            file.path.replace('\\', '/').contains("/debug/") &&
            !file.path.replace('\\', '/').contains("/release/") &&
            (file.name.contains("DndDebugOverride") || (file.extension == "class" && fileContainsNeedle(file, "DndDebugOverride")))
    }
    check(debugHasOverride) { "Debug output is missing DndDebugOverride" }
}

private fun assertReleaseHasNoFakeBilling(root: java.io.File) {
    val needles = listOf("FakeSubscriptionBilling", "FAKE_PURCHASE")
    val releaseFiles = root.walkTopDown().filter { file ->
        file.isFile &&
            file.path.replace('\\', '/').contains("/release/") &&
            !file.path.replace('\\', '/').contains("/debug/") &&
            file.extension in setOf("class", "jar", "dex", "apk")
    }.toList()
    check(releaseFiles.isNotEmpty()) { "No release class output under $root" }
    for (needle in needles) {
        val hit = releaseFiles.firstOrNull { file -> fileContainsNeedle(file, needle) }
        check(hit == null) { "Release output contains $needle in $hit" }
    }
    val debugHasFake = root.walkTopDown().any { file ->
        file.isFile &&
            file.path.replace('\\', '/').contains("/debug/") &&
            !file.path.replace('\\', '/').contains("/release/") &&
            (file.name.contains("FakeSubscriptionBilling") || (file.extension == "class" && fileContainsNeedle(file, "FakeSubscriptionBilling")))
    }
    check(debugHasFake) { "Debug output is missing FakeSubscriptionBilling" }
}

private fun fileContainsNeedle(file: java.io.File, needle: String): Boolean {
    val bytes = needle.toByteArray(Charsets.UTF_8)
    if (file.extension == "jar" || file.extension == "apk") {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.contains(needle)) return true
                    if (entry.isDirectory || entry.size > 5_000_000) continue
                    val data = zip.getInputStream(entry).readBytes()
                    if (indexOfBytes(data, bytes) >= 0) return true
                }
                false
            }
        } catch (error: Throwable) {
            false
        }
    }
    if (file.length() > 20_000_000) return false
    return indexOfBytes(file.readBytes(), bytes) >= 0
}

private fun indexOfBytes(data: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || data.size < needle.size) return -1
    for (start in 0..data.size - needle.size) {
        var match = true
        for (offset in needle.indices) {
            if (data[start + offset] != needle[offset]) {
                match = false
                break
            }
        }
        if (match) return start
    }
    return -1
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
