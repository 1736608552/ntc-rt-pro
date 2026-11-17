plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ntcrt.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ntcrt.pro"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksFile = file("release.keystore")
            val storePwd = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val aliasName = System.getenv("ANDROID_KEY_ALIAS")
            val keyPwd = System.getenv("ANDROID_KEY_PASSWORD")
            if (ksFile.exists() && !storePwd.isNullOrEmpty() && !aliasName.isNullOrEmpty() && !keyPwd.isNullOrEmpty()) {
                storeFile = ksFile
                storePassword = storePwd
                keyAlias = aliasName
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSign = signingConfigs.getByName("release")
            signingConfig = if (releaseSign.storeFile != null) releaseSign else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
}
