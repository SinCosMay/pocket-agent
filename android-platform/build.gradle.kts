plugins { id("com.android.library") }
android { namespace="dev.pocket.android"; compileSdk=36; defaultConfig { minSdk=31; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }; compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 } }
dependencies { api(project(":control-core")); androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation("androidx.test.ext:junit:1.2.1") }
