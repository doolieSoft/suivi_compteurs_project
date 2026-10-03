import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Coordonnées de la clé de publication. Le fichier n'est pas versionné : sans
// lui — sur un clone neuf, ou dans une intégration continue mal configurée —
// on retombe sur la clé de débogage, ce qui permet de compiler sans pouvoir
// publier par mégarde un APK signé avec la mauvaise clé.
val fichierCle = rootProject.file("keystore.properties")
val cle = Properties().apply {
    if (fichierCle.exists()) fichierCle.inputStream().use { load(it) }
}
val clePresente = fichierCle.exists() && rootProject.file("cle-release.jks").exists()

android {
    namespace = "be.suivicompteurs.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "be.suivicompteurs.app"
        // Android 8.0. En deçà, ni canaux de notification ni icônes adaptatives.
        minSdk = 26
        targetSdk = 36
        // Surchargeables depuis la ligne de commande, ce dont se sert la
        // publication automatique : l'étiquette Git donne le nom de version, et
        // le numéro d'exécution du workflow fournit un entier toujours
        // croissant — Android refuse d'installer un versionCode qui recule.
        //   ./gradlew assembleRelease -PsuiviVersionCode=7 -PsuiviVersionName=1.3
        versionCode = (findProperty("suiviVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("suiviVersionName") as String?) ?: "1.0"
    }

    signingConfigs {
        if (clePresente) {
            create("publication") {
                storeFile = rootProject.file("cle-release.jks")
                storePassword = cle.getProperty("motDePasseDepot")
                keyAlias = cle.getProperty("alias")
                keyPassword = cle.getProperty("motDePasseCle")
            }
        }
    }

    buildTypes {
        release {
            // Le modèle ML Kit et Room n'aiment pas l'obfuscation par défaut ;
            // l'enjeu est nul pour une application personnelle.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (clePresente) "publication" else "debug"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // Langues de l'interface ; le français reste la langue par défaut.
        localeFilters += listOf("fr", "en", "nl")
        // Déclare au système les langues proposées : Android 13 et au-delà
        // les offrent alors dans les réglages de l'application.
        generateLocaleConfig = true
    }

    buildFeatures {
        viewBinding = true
        // Les nouveaux écrans (gestion des données, analyses) sont écrits en
        // Compose ; les anciens restent en vues classiques.
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Lecture de l'orientation EXIF des photos.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Base locale : c'est elle qui rend la saisie possible sans le PC.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Synchronisation différée, relancée par le système quand le réseau revient.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Appareil photo.
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Reconnaissance de texte : modèle embarqué dans l'APK, donc hors ligne.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Écrans en Compose.
    val compose = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // org.json n'existe sur le PC qu'à l'état de bouchon : le vrai, pour lire
    // la référence du test de parité avec le moteur Python.
    testImplementation("org.json:json:20240303")
}

// Référence du moteur Python, produite par « python manage.py reference_moteur ».
val referenceMoteur = layout.buildDirectory.file("reference-moteur.json")
val referenceClasseur = layout.buildDirectory.file("export-reference.xlsx")
tasks.withType<Test>().configureEach {
    systemProperty("reference.moteur", referenceMoteur.get().asFile.absolutePath)
    systemProperty("reference.classeur", referenceClasseur.get().asFile.absolutePath)
    // Relancer le test quand la référence change, même sans toucher au code.
    inputs.property("referenceMoteur", referenceMoteur.get().asFile.let { if (it.exists()) it.lastModified() else 0L })
    testLogging { showStandardStreams = true }
}
