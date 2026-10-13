import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// FluentMai Android release signing
//
// The original FluentMai signing key is NEVER stored in this repository.
// Credentials are loaded at build time from a properties file that lives
// OUTSIDE the repo (default: <repo>/../fluentmai-signing-local/keystore.properties,
// and the keystore itself in the owner's private directory).
//
// If that file is absent, release signing is simply not configured: normal
// debug/development builds keep working exactly as before and no error is raised.
//
// The expected certificate fingerprint is a public constant. It is verified on
// every release build so a wrong / replaced key aborts the build immediately.
// ---------------------------------------------------------------------------

val expectedCertSha256 =
    "A6:F2:59:48:06:3D:A5:23:CD:42:2D:A4:01:D6:F0:19:79:92:6E:DC:AA:55:62:C8:D3:0E:BF:3D:D0:B8:98:0E"

// Resolution order for the signing properties file:
//   1. -PfluentmaiSigningProps=<path>   (explicit, highest priority)
//   2. FLUENTMAI_SIGNING_PROPERTIES env var
//   3. <repo>/../fluentmai-signing-local/keystore.properties  (default local drop)
//   4. <repo>/keystore.properties                              (legacy, git-ignored)
//
// An EXPLICIT request (1 or 2) is authoritative: if the caller names a
// credentials file that does not exist, the build fails loudly instead of
// silently falling back to a different key. Silent fallback could otherwise
// sign a release with an unintended identity.
val signingPropsFile: File? = run {
    val explicit = (project.findProperty("fluentmaiSigningProps") as String?)
        ?: System.getenv("FLUENTMAI_SIGNING_PROPERTIES")
    if (!explicit.isNullOrBlank()) {
        val f = file(explicit)
        require(f.isFile) {
            "FluentMai release signing: explicitly requested diagnostics properties file " +
                "does not exist. Refusing to fall back to another keystore."
        }
        f
    } else {
        listOf(
            rootProject.file("../fluentmai-signing-local/keystore.properties"),
            rootProject.file("keystore.properties"),
        ).firstOrNull { it.isFile }
    }
}

val signingProps = Properties().apply {
    signingPropsFile?.inputStream()?.use { load(it) }
}

val hasReleaseSigning = signingPropsFile != null && signingProps.getProperty("storeFile") != null

android {
    namespace = "dev.fluentmai.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.fluentmai.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.3.2-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").assets.srcDir(rootProject.file("fixtures"))
    }

    buildFeatures {
        compose = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("fluentmaiRelease") {
                val rawStorePath = signingProps.getProperty("storeFile")!!
                // storeFile may be absolute or relative to the directory holding the properties file.
                val resolved = File(rawStorePath).let { f ->
                    if (f.isAbsolute) f
                    else File(signingPropsFile!!.parentFile, rawStorePath)
                }
                require(resolved.isFile) {
                    "FluentMai release signing: keystore not found at resolved path."
                }
                storeFile = resolved
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias") ?: "androiddebugkey"
                keyPassword = signingProps.getProperty("keyPassword")
                storeType = signingProps.getProperty("storeType") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("fluentmaiRelease")
            }
        }
        getByName("debug") {
            // Untouched: debug still uses the standard Android debug keystore.
            // Supplying signing material must never disturb normal development builds.
        }
    }
}

// ---------------------------------------------------------------------------
// Release-build guard: verify the signing certificate fingerprint before the
// APK is packaged. Any mismatch aborts the build.
//
// The task is attached directly to the release packaging tasks (rather than
// via preBuild) so it always runs for a release build and never slows down or
// affects debug / development builds.
// ---------------------------------------------------------------------------
val verifySigningFingerprint by tasks.registering {
    description = "Verifies the FluentMai release keystore certificate SHA-256 fingerprint."
    group = "verification"

    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "FluentMai release signing is not configured. " +
                    "Provide keystore.properties outside the repo " +
                    "(see docs/WINDOWS_ANDROID_SIGNING.md). Refusing to build an unsigned/mis-signed release."
            )
        }
        val cfg = android.signingConfigs.getByName("fluentmaiRelease")
        val javaHome = System.getProperty("java.home")
        val ktExe = File(javaHome, "bin/keytool.exe").let { if (it.isFile) it else File(javaHome, "bin/keytool") }
        require(ktExe.isFile) { "keytool not found under $javaHome; cannot verify signing fingerprint." }

        val out = ByteArrayOutputStream()
        exec {
            commandLine(
                ktExe.absolutePath, "-list", "-v",
                "-keystore", cfg.storeFile!!.absolutePath,
                "-storetype", cfg.storeType,
                "-alias", cfg.keyAlias,
                "-storepass", cfg.storePassword,
            )
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        val text = out.toString("UTF-8")
        if (!text.contains("PrivateKeyEntry")) {
            throw GradleException(
                "FluentMai release signing: alias '${cfg.keyAlias}' is not a PrivateKeyEntry. " +
                    "Refusing to sign with a public-certificate-only entry."
            )
        }
        val actual = text.replace(":", "").lowercase()
        if (!actual.contains(expectedCertSha256.replace(":", "").lowercase())) {
            throw GradleException(
                "FluentMai release signing: certificate fingerprint mismatch! " +
                    "Expected $expectedCertSha256. Aborting to protect upgrade compatibility."
            )
        }
        logger.lifecycle("FluentMai signing: certificate fingerprint verified ($expectedCertSha256).")
    }
}

// Attach the guard to every release packaging / bundling task.
tasks.configureEach {
    if (name.startsWith("package") && name.endsWith("Release") || name == "bundleRelease") {
        dependsOn(verifySigningFingerprint)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation(project(":core:importer"))
    implementation(project(":core:privacy"))
    implementation(project(":core:upload"))
    implementation(project(":feature:home"))
    implementation(project(":feature:import"))
    implementation(project(":feature:scores"))
    implementation(project(":feature:quarantine"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:tools"))

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
