plugins {
    id("com.android.application")
}

val appVersionCode = 12
val appVersionName = "1.10"
val uiBuildTag = "v1.10-safari-suggestions-2026-06-24"

android {
    namespace = "com.kernel.browser"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.kernel.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UI_BUILD_TAG", "\"$uiBuildTag\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:152.0.20260617213557")

    testImplementation("junit:junit:4.13.2")
}

tasks.register<Copy>("copyInstallableReleaseApks") {
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    abis.forEach { abi ->
        from(layout.buildDirectory.dir("outputs/apk/release")) {
            include("app-$abi-release.apk")
            rename { "KernelBrowser-$appVersionName-$uiBuildTag-$abi.apk" }
        }
    }
    into(layout.buildDirectory.dir("outputs/installable"))
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("copyInstallableReleaseApks")
    }
}
