plugins { id("com.android.application") }

android {
    namespace = "com.spacewise.cleaner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.spacewise.cleaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.10.0")
}
