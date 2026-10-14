import java.util.Properties

plugins { id("com.android.application") }

val signingFile = providers.environmentVariable("ROUTE_SIGNING_PROPERTIES").orNull
val signing = Properties()
if (signingFile != null) file(signingFile).inputStream().use { signing.load(it) }

android {
    namespace = "io.github.sorubedo.routeplanner"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.sorubedo.routeplanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (signingFile != null) {
        signingConfigs.create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("com.tencent.map:tencent-map-vector-sdk:5.6.0")
    testImplementation("junit:junit:4.13.2")
}
