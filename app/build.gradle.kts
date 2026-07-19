plugins { id("com.android.application") }

android {
    namespace = "com.spacewise.cleaner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.spacewise.cleaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.1.0"
    }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
