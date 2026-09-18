plugins {
    id("com.android.application")
}

android {
    namespace = "com.company.tap.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.company.tap.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(project(":protocol"))
    androidTestImplementation(project(":driver:command-engine"))
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
