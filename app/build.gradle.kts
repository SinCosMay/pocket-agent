plugins { id("com.android.application") }
android {
    namespace = "dev.pocket.agent"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.pocket.agent"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.4-display-preview.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true; aidl = true }
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
        release { testProguardFiles.add(file("test-proguard-rules.pro")); signingConfig = signingConfigs.getByName("distribution"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    splits { abi { isEnable=true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk=true } }
    testBuildType = "release"
    lint { textReport=true; textOutput=file("build/reports/lint-results-release.txt") }
    packaging { jniLibs { useLegacyPackaging = true }; resources { excludes += "META-INF/DEPENDENCIES" } }
}
dependencies {
    implementation(project(":android-platform"))
    implementation("com.caoccao.javet:javet-node-android-i18n:6.0.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
