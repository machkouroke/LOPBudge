plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.lop.budget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lop.budget"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.lop.budget.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // `MigrationTestHelper` ne sait lire les schémas exportés que depuis les assets. Les assets du
    // source set `test` ne sont pas fusionnés pour les tests unitaires : on les rattache donc au
    // variant **debug**, que Robolectric exécute. La release n'embarque rien.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    testOptions {
        // Nécessaire pour que Robolectric voie les assets, donc les schémas Room exportés : sans
        // cela, le test de migration ne trouve pas la description de la version précédente.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("java.net.preferIPv4Stack", "true")
            test.systemProperty("java.net.preferIPv4Addresses", "true")
            test.jvmArgs(
                "-Djava.net.preferIPv4Stack=true",
                "-Djava.net.preferIPv4Addresses=true"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:strongSkipping=true"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/junit-jupiter-api.kotlin_module"
        }
        jniLibs {
            // `false` : les .so restent non compressés et sont chargés directement depuis l'APK
            // au lieu d'être extraits sur le disque à l'installation. Avec onnxruntime et MLKit
            // cela évite de stocker les bibliothèques natives en double, et c'est la condition
            // pour que `android.useFullNativeAlignment` (alignement 16 Ko) serve à quelque chose.
            // Supporté depuis l'API 23, or minSdk = 26.
            useLegacyPackaging = false
        }
    }
}

/**
 * Room écrit à chaque compilation la description de la base dans `app/schemas`.
 *
 * Sans ces fichiers, un test de migration n'a aucun moyen de reconstruire la base telle qu'elle
 * était à la version précédente : il faudrait recopier le schéma à la main dans le test, où il se
 * périmerait en silence. Les fichiers produits sont versionnés avec le code.
 *
 * Le **plugin** est utilisé plutôt que l'option `room.schemaLocation` de KSP : lui seul déclare le
 * répertoire comme une vraie sortie de tâche. Avec l'option seule, le fichier n'est pas régénéré
 * quand il disparaît, et la fusion des assets peut embarquer la version précédente — un test de
 * migration valide alors contre une description périmée, avec un tour de retard sur le code.
 * Constaté le 17 septembre 2026.
 */
room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Le schéma doit être écrit **avant** la fusion des assets.
 *
 * Le plugin Room alimente tout seul les assets des tests instrumentés, mais pas ceux des tests
 * unitaires : sans cet ordre, la fusion embarque le schéma de la compilation précédente. Le test de
 * migration valide alors contre une description périmée — avec un tour de retard sur le code, ce
 * qui donne aussi bien des rouges trompeurs que des verts imméritée.
 */
tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn("copyRoomSchemas")
}

dependencies {
    // Force Activity and NavigationEvent versions to avoid AGP 8.9+ requirement
    implementation("androidx.activity:activity:1.9.2") {
        version { strictly("1.9.2") }
    }
    implementation("androidx.activity:activity-compose:1.9.2") {
        version { strictly("1.9.2") }
    }
    implementation("androidx.activity:activity-ktx:1.9.2") {
        version { strictly("1.9.2") }
    }
    // NavigationEvent is usually brought in by Activity 1.10+
    // We try to force it to a version that doesn't exist or a known stable one if possible,
    // but better to force Activity down first.
    // If it's still there, we might need to exclude it or force it to a lower version.

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Haze (liquid glass)
    val hazeVersion = "1.7.2"
    implementation("dev.chrisbanes.haze:haze:$hazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$hazeVersion")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // DataStore (settings : devise, clé Gemini, thème)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Graphics Path (16 KB alignment)
    implementation("androidx.graphics:graphics-path:1.1.0")

    // Glance (widgets)
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    // Réseau pour l'IA Gemini
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // AI & ML (Hybrid Strategy)
    implementation("com.google.mlkit:entity-extraction:16.0.0-beta6")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.qameta.allure:allure-junit4:2.35.4")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.room:room-testing:2.7.0")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("io.mockk:mockk:1.13.12")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // `io.mockk:mockk-android` a été retiré le 21 septembre 2026 : il n'était utilisé par aucun
    // test instrumenté et embarquait `libmockkjvmtiagent.so`, seule bibliothèque native de l'APK
    // de test. Non alignée sur 16 Ko, elle déclenche la boîte système « Compatibilité des applis
    // Android » sur Android 15+, qui prend le focus et intercepte les gestes : toute la suite
    // instrumentée échouait dessus. `testImplementation("io.mockk:mockk")` reste en place pour
    // les tests unitaires, qui ne chargent aucune bibliothèque native.
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.54")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.54")
}

tasks.withType<Test>().configureEach {
    systemProperty("java.net.preferIPv4Stack", "true")
    systemProperty("java.net.preferIPv4Addresses", "true")
    jvmArgs("-Djava.net.preferIPv4Stack=true", "-Djava.net.preferIPv4Addresses=true")
}
