plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    compileSdk = 35

    defaultConfig {
        namespace = "com.arasthel.spannedgridlayoutmanager"
        minSdk = 21
        targetSdk = 34
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "tk.zwander.spannedgridlayoutmanager"
                artifactId = "final"
                version = "1.0"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlin.stdlib.jdk8)
}