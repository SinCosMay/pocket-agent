plugins { id("com.android.library") }
android { namespace="dev.pocket.android"; compileSdk=36; defaultConfig { minSdk=31 }; compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 } }
dependencies { api(project(":control-core")) }
