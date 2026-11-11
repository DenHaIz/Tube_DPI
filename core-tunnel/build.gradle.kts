plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tube.tunnel"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            ndkBuild {
                // Без исполняемого hev-socks5-tunnel-bin: нужна только библиотека с JNI.
                targets += "hev-socks5-tunnel"
                arguments += listOf("REV_ID=tube", "-j${Runtime.getRuntime().availableProcessors()}")
            }
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    defaultConfig { consumerProguardFiles("consumer-rules.pro") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
