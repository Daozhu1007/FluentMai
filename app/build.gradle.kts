plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.fluentmai.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.fluentmai.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.2.5-android-beta.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").assets.srcDir(rootProject.file("fixtures"))
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
