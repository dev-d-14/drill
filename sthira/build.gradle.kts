plugins {
    id("com.android.application")
}

android {
    namespace = "app.sthira"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sthira"
        minSdk = 26
        targetSdk = 35
        // Every CI build gets its own number, so the installed build can be told apart from the last one.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }

    // Same fixed debug key as Drill, so every build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
