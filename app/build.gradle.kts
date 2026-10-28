plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.example.okko"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

       signingConfigs {
        create("release") {
            if (project.hasProperty("MYAPP_RELEASE_STORE_FILE")) {
                storeFile = file(project.property("MYAPP_RELEASE_STORE_FILE") as String)
                storePassword = project.property("MYAPP_RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("MYAPP_RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("MYAPP_RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }


    kotlinOptions {
        jvmTarget = "21"
    }

    defaultConfig {
        applicationId = "ru.example.okko"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // media3-ui приносит строки на десятках языков, оставляем нужные
        resourceConfigurations += listOf("ru", "en")
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
