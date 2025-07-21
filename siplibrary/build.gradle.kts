plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.eddyslarez.siplibrary"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    implementation("com.shepeliev:webrtc-kmp:0.125.9")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.eddyslarez"
            artifactId = "sip-library"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("SIP Library for Android")
                description.set("Librería para WebRTC y WebSocket en Android con Compose.")
                url.set("https://github.com/eddyslarez/sip-library")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("eddyslarez")
                        name.set("Eddys Larez")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/eddyslarez/sip-library.git")
                    developerConnection.set("scm:git:ssh://github.com/eddyslarez/sip-library.git")
                    url.set("https://github.com/eddyslarez/sip-library")
                }
            }
        }
    }
}