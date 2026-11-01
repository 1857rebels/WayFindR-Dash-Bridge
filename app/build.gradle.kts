plugins {
    id("com.android.application")
}

android {
    namespace = "com.wayfindr.hus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wayfindr.hus"
        // WifiNetworkSpecifier (joining the dash's Wi-Fi as a per-app network) needs API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload build: signed with the debug key so it installs without setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Unit tests run on the laptop's JVM; android.util.Log there just returns defaults.
    testOptions.unitTests.isReturnDefaultValues = true
}

// APKs come out as WayFindR-Dash-Bridge-release.apk / -debug.apk.
base.archivesName = "WayFindR-Dash-Bridge"

dependencies {
    testImplementation("junit:junit:4.13.2")
}
