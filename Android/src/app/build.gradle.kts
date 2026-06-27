/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

android {
  namespace = "app.kaiwa"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "app.kaiwa"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "1.0.0"

    // Ship a single 64-bit ABI. Every Android 12+ phone is arm64, and this keeps the APK from
    // ballooning with native libs for ABIs no real device uses.
    ndk { abiFilters += "arm64-v8a" }

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "app.kaiwa.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
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
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  // Full protobuf-java (not javalite) so it can host both the app's lite-generated protos and
  // libSQL's full-generated protos. 3.25.x keeps BOTH GeneratedMessageV3 (libSQL) and
  // GeneratedMessageLite (app); 4.26.x dropped GeneratedMessageV3.
  implementation("com.google.protobuf:protobuf-java:3.25.5")
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.documentfile)
  implementation(libs.moshi.kotlin)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  // On-device, GMS-free runtime for the lightweight MobileCLIP multimodal embedder (RAG).
  implementation(libs.onnxruntime.android)
  // GMS-free on-device speech recognition (works on GrapheneOS where SpeechRecognizer is absent).
  implementation(libs.vosk.android)
  // GMS-free on-device text-to-speech (sherpa-onnx, static-linked ORT). Kotlin API jar; the matching
  // arm64-v8a native lib lives in src/main/jniLibs. Speaks MMS voices downloaded at runtime.
  implementation(files("libs/sherpa-onnx-1.13.3.jar"))
  // Pure-Java tar + bzip2, to extract the .tar.bz2 voice models (java.util.zip can't do bz2).
  implementation(libs.commons.compress)
  // Turso / libSQL embedded database for chat history (local now, cloud-sync capable later).
  // Exclude its bundled protobuf-java so the whole app resolves to a single protobuf version.
  implementation("tech.turso.libsql:libsql:0.1.0") {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
  }
}

// Keep a single protobuf runtime across all dependencies (libSQL pulls full protobuf-java; the app
// and various Google libs pull javalite). Force everything onto full protobuf-java.
configurations.all {
  exclude(group = "com.google.protobuf", module = "protobuf-javalite")
  resolutionStrategy { force("com.google.protobuf:protobuf-java:3.25.5") }
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
