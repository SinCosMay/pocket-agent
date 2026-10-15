plugins { id("com.android.application") }
android {
    namespace = "dev.pocket.agent"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pocket.agent"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("distribution") {
            storeFile = file(System.getenv("APK_KEYSTORE") ?: "missing-keystore.jks")
            storePassword = System.getenv("APK_STORE_PASSWORD")
            keyAlias = "pocket-agent"
            keyPassword = System.getenv("APK_KEY_PASSWORD")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("distribution"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    splits { abi { isEnable=true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk=true } }
    testBuildType = "release"
    lint { textReport=true; textOutput=file("build/reports/lint-results-release.txt") }
    packaging { jniLibs { useLegacyPackaging = true }; resources { excludes += "META-INF/DEPENDENCIES" } }
}
dependencies {
    implementation(project(":android-platform"))
    implementation("com.caoccao.javet:javet-node-android:6.0.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
