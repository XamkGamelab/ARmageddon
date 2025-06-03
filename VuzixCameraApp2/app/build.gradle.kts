plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.vuzixcameraapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.vuzixcameraapp"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-extensions:1.4.2")
    implementation ("org.tensorflow:tensorflow-lite:+")
    implementation("org.tensorflow:tensorflow-lite-gpu:+")
    implementation("org.tensorflow:tensorflow-lite-support:+")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:+")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:+")
}