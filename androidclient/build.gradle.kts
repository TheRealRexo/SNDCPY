plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.0"
}

android {
    namespace = "com.sndcpy.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sndcpy.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.11.0")

    // Security hardening: pin transitive dependencies to versions that patch known CVEs.
    // This suppresses Dependabot alerts for vulnerable transitive libs.
    constraints {
        // Netty: all known CVEs (SNI bypass, HTTP/2 DoS, Rapid Reset, bytebuf leak, etc.) fixed in 4.1.115+
        implementation("io.netty:netty-handler:4.1.115.Final")
        implementation("io.netty:netty-codec-http:4.1.115.Final")
        implementation("io.netty:netty-codec-http2:4.1.115.Final")
        implementation("io.netty:netty-codec:4.1.115.Final")
        // Protobuf: uncontrolled resource consumption / DoS fixed in 3.25.0+
        implementation("com.google.protobuf:protobuf-java:3.25.5")
        // Guava: CompactHashMap deserialization uncontrolled allocation fixed in 32.1.3+
        implementation("com.google.guava:guava:33.3.1-jre")
        // jose4j: weak crypto and compressed JWE DoS fixed in 0.9.6+
        implementation("org.bitbucket.b_c:jose4j:0.9.6")
        // commons-io: XmlStreamReader DoS fixed in 2.16.0+
        implementation("commons-io:commons-io:2.16.1")
        // xerces: infinite loop fixed in 2.12.2+
        implementation("xerces:xercesImpl:2.12.2")
        // JDOM: XXE injection fixed in 2.0.7.1+
        implementation("org.jdom:jdom2:2.0.7.1")
    }
}