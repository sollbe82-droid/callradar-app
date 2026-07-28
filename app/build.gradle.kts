import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.callradar.app"
    compileSdk = 36
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    signingConfigs {
        create("release") {
            storeFile = file("../callradar-release.jks")
            storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
            keyAlias = "callradar"
            keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
        }
    }
    defaultConfig {
        applicationId = "com.callradar.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 21
        versionName = "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // [v22] 카카오맵 네이티브 앱 키 — local.properties의 KAKAO_NATIVE_KEY 사용(코드/깃에 하드코딩 X)
        buildConfigField("String", "KAKAO_NATIVE_KEY", "\"${keystoreProps.getProperty("KAKAO_NATIVE_KEY") ?: ""}\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.zxing:core:3.5.3")   // [v19] 명함 QR(vCard) 생성
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.kakao.maps.open:android:2.13.2")   // [v22] 카카오맵 SDK v2 (내 운행 지도)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
