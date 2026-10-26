plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.shieldtools.launchermanager"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shieldtools.launchermanager"
        minSdk = 30
        targetSdk = 34
        versionCode = 13
        versionName = "1.0.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    sourceSets["main"].java.srcDir("../shared/src")
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21") }








