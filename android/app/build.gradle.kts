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
val signatureRelease = if (clePresente) "publication" else "debug"

android {
    namespace = "be.suivicompteurs.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "be.suivicompteurs.app"
        // Android 8.0. En deçà, ni canaux de notification ni icônes adaptatives.
        minSdk = 26
        targetSdk = 36
        // À relever avant chaque étiquette : versionCode toujours croissant
        // (Android refuse d'installer un numéro qui recule), versionName égal à
        // l'étiquette sans son « v ». F-Droid lit ces deux lignes pour repérer
        // les nouvelles versions ; la publication automatique refuse une
        // étiquette qui ne correspond pas.
        versionCode = 124
        versionName = "1.3.10"
        // Surchargeables pour un essai :
        //   ./gradlew assembleCompletRelease -PsuiviVersionCode=7 -PsuiviVersionName=1.3
        (findProperty("suiviVersionCode") as String?)?.let { versionCode = it.toInt() }
        (findProperty("suiviVersionName") as String?)?.let { versionName = it }
        // Lien « Soutenir le développement », fourni à la compilation : la
        // version GitHub le reçoit, celle du Play Store non — Google n'y admet
        // pas de paiement hors de son propre système. Vide : pas de bouton.
        val lienDons = (findProperty("suiviLienDons") as String?).orEmpty()
        buildConfigField("String", "LIEN_DONS", "\"" + lienDons.replace("\"", "") + "\"")
    }

    // Deux variantes, identiques hormis la lecture automatique de l'index :
    //  - « complet » (Play Store, GitHub) la fait avec ML Kit de Google ;
    //  - « libre » (F-Droid) n'embarque aucun composant propriétaire : l'index
    //    se tape à la main, et l'appareil photo n'est pas demandé.
    flavorDimensions += "distribution"
    productFlavors {
        create("complet") { dimension = "distribution" }
        create("libre") { dimension = "distribution" }
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
            // R8 retire le code inutilisé et réduit l'APK (demandé par F-Droid).
            // ML Kit, Room, OkHttp et WorkManager fournissent leurs propres
            // règles ; les nôtres sont dans proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sur une ligne : F-Droid retire les lignes de signature avant de
            // compiler, pour signer lui-même ; une instruction coupée en
            // plusieurs lignes laisserait des morceaux orphelins.
            signingConfig = signingConfigs.getByName(signatureRelease)
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
        buildConfig = true
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
    "completImplementation"("com.google.mlkit:text-recognition:16.0.1")

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
    // la référence du test de parité (src/test/resources).
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}
