plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.geely.navigationbridge"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.geely.navigationbridge"
        minSdk = 18
        targetSdk = 18
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs.getByName("debug") {
        enableV1Signing = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
