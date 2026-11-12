plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "pl.agh.meditracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.agh.meditracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // adres serwera kont/kopii/katalogu; nadpisanie: -PserverUrl=http://10.0.2.2:8765 (bez końcowego ukośnika)
        val serverUrl = (project.findProperty("serverUrl") as String?)
            ?: "https://kryzys-server.tail9dde62.ts.net:10000"
        buildConfigField("String", "SERVER_URL", "\"${serverUrl.trimEnd('/')}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    // bcprov i jspecify mają ten sam manifest OSGi (bez znaczenia na Androidzie)
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
}

// schemat user.db w repo, żeby kolejne wersje bazy miały z czym robić migracje
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
