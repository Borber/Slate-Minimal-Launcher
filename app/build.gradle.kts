import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.slate.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.borber.slate.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        // Only declare the release signing config when keystore.properties is present.
        // F-Droid CI (and any contributor build) runs without that file; configuring the
        // `storeFile` from an empty properties map would NPE at configure time. Skipping the
        // config here lets F-Droid build an unsigned release APK which it then signs with
        // its own key, the normal F-Droid flow. Local release builds are unaffected.
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // findByName returns null when the signing config wasn't created (no keystore);
            // assigning null leaves the release APK unsigned for F-Droid's signing step.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    val appName = groovy.xml.XmlParser().parse(file("src/main/res/values/strings.xml"))
        .children()
        .filterIsInstance<groovy.util.Node>()
        .firstOrNull { it.attribute("name") == "app_name" }
        ?.text()
        ?.lowercase()
        ?: "app"

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "${appName}_${variant.versionName}_${variant.versionCode}_${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // AGP 8.1+ embeds the current git revision into META-INF/version-control-info.textproto.
    // Inside F-Droid's sandbox there is no git context, so their build emits a placeholder
    // ("generate_error_reason: NO_VALID_GIT_FOUND") while our local build emits the real SHA.
    // Two different bytes break reproducible-builds verification. Drop the file on every build
    // so the user-signed APK and the F-Droid-built APK are byte-identical.
    packaging {
        resources {
            excludes += "META-INF/version-control-info.textproto"
        }
    }

    // AGP 8.x writes a "Dependency metadata" extra block into the APK Signing Block listing
    // every Gradle dependency plus its checksum. F-Droid's scanner rejects any non-standard
    // signing block as a privacy / supply-chain concern, so disable both APK and AAB embedding.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.recyclerview)
}
