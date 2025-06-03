import com.android.build.api.dsl.Packaging
import java.util.Properties

// Lire la clé API depuis local.properties
val orsApiKey = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { Properties().apply { load(it.inputStream()) } }
    ?.getProperty("ORS_API_KEY") ?: throw GradleException("ORS_API_KEY not found in local.properties")

// Lire la clé API depuis local.properties
val ghApiKey = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { Properties().apply { load(it.inputStream()) } }
    ?.getProperty("GH_API_KEY") ?: throw GradleException("GH_API_KEY not found in local.properties")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.quentin.navigationapp"
    compileSdk = 35

    defaultConfig {
        vectorDrawables {
            useSupportLibrary = true
        }
        applicationId = "com.quentin.navigationapp"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ORS_API_KEY", "\"$orsApiKey\"")
        buildConfigField("String", "GH_API_KEY", "\"$ghApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources {
            pickFirsts.add("META-INF/gradle/incremental.annotation.processors")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.coordinatorlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Retrofit pour appels HTTP
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Google Play Services - GPS
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Coroutines Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ViewModel + LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    //pour la vue, le recucleView
    implementation ("androidx.recyclerview:recyclerview:1.2.1")

    //pour l'intégration d'une map visuel:
    implementation ("org.osmdroid:osmdroid-android:6.1.16")

    implementation ("com.google.dagger:hilt-android:2.44")
    implementation ("com.google.dagger:hilt-android-compiler:2.44")

    // Pour l’injection dans les ViewModels
    implementation ("androidx.hilt:hilt-lifecycle-viewmodel:1.0.0-alpha03")
    implementation ("androidx.hilt:hilt-compiler:1.0.0")

    implementation("com.google.android.material:material:1.8.0")


}