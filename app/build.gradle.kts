plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.srr.passwords"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "ru.srr.passwords"
        minSdk = 26
        targetSdk = 35

        val appVersionName = "1.4.1"
        versionCode = 42
        versionName = appVersionName
        resValue("string", "app_name", "Пароли")
        resValue("string", "app_version", appVersionName)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AUTHOR", "\"СпустяРуковаРекордс\"")
        buildConfigField("String", "AUTHOR_ID", "\"srr-passwords-2026\"")
        buildConfigField("String", "AUTHOR_MARK", "\"© СпустяРуковаРекордс\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    sourceSets {
        getByName("test") {
            resources.srcDir("src/sharedTest/resources")
        }
        getByName("androidTest") {
            assets.srcDir("src/sharedTest/resources")
        }
    }
}

android.applicationVariants.configureEach {
    val vName = versionName
    outputs.configureEach {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName = "passwords-${vName}.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
}

tasks.register("dumpVault") {
    group = "verification"
    description = "Copy vault CSV/JSON off the device before any uninstall"
    doLast {
        exec {
            workingDir = rootProject.projectDir
            commandLine("sh", "scripts/dump-vault.sh")
        }
    }
}

afterEvaluate {
    listOf("uninstallDebug", "uninstallRelease", "uninstallAll").forEach { name ->
        tasks.findByName(name)?.let { task ->
            task.dependsOn("dumpVault")
            task.doFirst {
                if (project.findProperty("allowUninstall") != "true") {
                    throw GradleException(
                        "ru.srr.passwords не удалять. Дамп: ./gradlew dumpVault. " +
                            "Если правда надо снести пакет: -PallowUninstall=true"
                    )
                }
            }
        }
    }
}
