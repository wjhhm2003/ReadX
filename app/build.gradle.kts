import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val bundledOcr = providers.gradleProperty("bundledOcr").map { it.toBooleanStrict() }.orElse(false).get()

android {
    namespace = "io.readx.app"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "io.readx.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 14
        versionName = if (bundledOcr) "0.7.2-ocr" else "0.7.2"
        buildConfigField("boolean", "BUNDLED_OCR", bundledOcr.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-preview"
        }
    }
    if (bundledOcr) sourceSets.getByName("main").assets.directories.add("src/ocrBundled/assets")
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.github.skydoves:colorpicker-compose:1.1.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.01.00"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

if (bundledOcr) {
    val verifyModels = tasks.register("verifyBundledOcrModels") {
        val assets = layout.projectDirectory.dir("src/ocrBundled/assets/ocr")
        inputs.dir(assets)
        doLast {
            val manifest = groovy.json.JsonSlurper().parse(assets.file("manifest.json").asFile) as Map<*, *>
            val models = manifest["models"] as Map<*, *>
            for (name in listOf("chi_sim", "chi_tra", "eng")) {
                val model = assets.file("$name.traineddata").asFile
                check(model.isFile) { "Missing $name.traineddata. Run scripts/prepare-ocr-models.ps1 first." }
                val entry = models[name] as Map<*, *>
                check(model.length() == (entry["bytes"] as Number).toLong()) { "Invalid $name size" }
                val digest = MessageDigest.getInstance("SHA-256")
                model.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(hash == entry["sha256"]) { "Invalid $name SHA-256" }
            }
        }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyModels) }
}
