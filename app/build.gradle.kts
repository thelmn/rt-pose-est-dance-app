plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pause.dance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pause.dance"
        minSdk = 24
        targetSdk = 34
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            pickFirsts += "META-INF/native-image/android-arm64/jnijavacpp/jni-config.json"
            pickFirsts += "META-INF/native-image/android-arm64/jnijavacpp/reflect-config.json"
            pickFirsts += "META-INF/native-image/android-x86_64/jnijavacpp/jni-config.json"
            pickFirsts += "META-INF/native-image/android-x86_64/jnijavacpp/reflect-config.json"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation (fileTree("libs") { include("*.jar") })
    implementation(files("libs/mmdeploy.jar"))
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.glide)

    implementation(libs.javacv)
    implementation(libs.javacpp)
    implementation(libs.openblas)
    implementation(variantOf(libs.openblas) { classifier("android-arm64") })
    implementation(variantOf(libs.openblas) { classifier("android-x86_64") })
    implementation(libs.ffmpeg)
    implementation(variantOf(libs.ffmpeg) { classifier("android-arm64") })
    implementation(variantOf(libs.ffmpeg) { classifier("android-x86_64") })
    implementation(libs.opencv)
    implementation(variantOf(libs.opencv) { classifier("android-arm64") })
    implementation(variantOf(libs.opencv) { classifier("android-x86_64") })
    implementation(libs.videoinput)

    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.guava)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.camera2)
    annotationProcessor(libs.glide.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}