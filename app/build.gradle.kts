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
        versionCode = 68
        versionName = "2.6.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // [v22] 카카오맵 네이티브 앱 키 — local.properties의 KAKAO_NATIVE_KEY 사용(코드/깃에 하드코딩 X)
        buildConfigField("String", "KAKAO_NATIVE_KEY", "\"${keystoreProps.getProperty("KAKAO_NATIVE_KEY") ?: ""}\"")
        // [v43] 카카오 네이티브 로그인 SDK 리다이렉트 스킴(kakao{네이티브키}://oauth) 매니페스트 치환값
        manifestPlaceholders["KAKAO_NATIVE_KEY"] = keystoreProps.getProperty("KAKAO_NATIVE_KEY") ?: ""
    }
    // [스토어 분기] play = 접근성 OFF(구글 정책 안전), onestore = 접근성 ON(v9 완전자동)
    // 접근성 서비스(NaviIntentReceiver) 등록은 각 flavor의 매니페스트에서만 병합됨.
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
        }
        create("onestore") {
            dimension = "store"
            versionNameSuffix = "-onestore"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("com.kakao.sdk:v2-user:2.23.2")          // [v43] 카카오 네이티브 로그인(1탭·토큰유지)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
