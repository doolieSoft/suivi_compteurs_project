package be.suivicompteurs.app.classeur

import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Lecture minimale d'un classeur Excel (.xlsx).
 *
 * Un .xlsx n'est qu'une archive ZIP de fichiers XML : on en tire les valeurs
 * des cellules, sans styles ni formules (seule la dernière valeur calculée,
 * si le fichier en contient une). Cela suffit à relire un export de
 * l'application, y compris après passage par Excel ou Google Sheets, sans
 * embarquer une bibliothèque de plusieurs mégaoctets.
 */
class Classeur private constructor(private val feuilles: Map<String, Feuille>) {

    val noms: List<String> get() = feuilles.keys.toList()

    operator fun get(nom: String): Feuille? = feuilles[nom]

    companion object {
        fun lire(flux: InputStream): Classeur {
            val fichiers = HashMap<String, ByteArray>()
            ZipInputStream(flux).use { zip ->
                while (true) {
                    val entree = zip.nextEntry ?: break
                    if (entree.name.endsWith(".xml") || entree.name.endsWith(".rels")) {
                        fichiers[entree.name.removePrefix("/")] = zip.readBytes()
                    }
                }
            }
            val classeur = fichiers["xl/workbook.xml"]
                ?: throw ClasseurIllisible("Ce fichier n'est pas un classeur Excel (.xlsx).")

            val chaines = fichiers["xl/sharedStrings.xml"]?.let(::lireChaines) ?: emptyList()
            val cibles = fichiers["xl/_rels/workbook.xml.rels"]?.let(::lireRelations) ?: emptyMap()

            val feuilles = LinkedHashMap<String, Feuille>()
            for ((nom, relation) in lireFeuilles(classeur)) {
                val cible = cibles[relation] ?: continue
                val chemin = if (cible.startsWith("/")) cible.removePrefix("/") else "xl/$cible"
                val contenu = fichiers[chemin] ?: continue
                feuilles[nom] = lireFeuille(contenu, chaines)
            }
            return Classeur(feuilles)
        }
    }
}

class ClasseurIllisible(message: String) : Exception(message)

/** Valeurs d'une feuille, indexées par ligne puis colonne (à partir de 0). */
class Feuille(private val cellules: Map<Int, Map<Int, Any>>) {

    val lignes: List<Int> get() = cellules.keys.sorted()

    /** Valeur brute : texte, nombre (Double) ou booléen. */
    fun valeur(ligne: Int, colonne: Int): Any? = cellules[ligne]?.get(colonne)

    fun ligne(ligne: Int): List<Any?> {
        val valeurs = cellules[ligne] ?: return emptyList()
        val derniere = valeurs.keys.maxOrNull() ?: return emptyList()
        return (0..derniere).map { valeurs[it] }
    }
}

// ---------------------------------------------------------------------------
// Conversions : Excel stocke les dates en nombre de jours depuis 1899-12-30
// ---------------------------------------------------------------------------

private val ORIGINE_EXCEL: LocalDate = LocalDate.of(1899, 12, 30)

fun enDate(valeur: Any?): LocalDate? = when (valeur) {
    is Double -> if (valeur in 1.0..2_958_465.0) ORIGINE_EXCEL.plusDays(valeur.toLong()) else null
    is String -> valeur.trim().let { texte ->
        runCatching { LocalDate.parse(texte.take(10)) }.getOrNull()
            ?: Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").matchEntire(texte)?.destructured
                ?.let { (j, m, a) -> runCatching { LocalDate.of(a.toInt(), m.toInt(), j.toInt()) }.getOrNull() }
    }
    else -> null
}

fun enNombre(valeur: Any?): Double? = when (valeur) {
    is Double -> valeur
    is String -> valeur.trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

fun enTexte(valeur: Any?): String = when (valeur) {
    null -> ""
    is Double -> if (valeur % 1.0 == 0.0) valeur.toLong().toString() else valeur.toString()
    else -> valeur.toString().trim()
}

// ---------------------------------------------------------------------------
// Lecture des fichiers XML de l'archive
// ---------------------------------------------------------------------------

private fun analyser(contenu: ByteArray, gestionnaire: DefaultHandler) {
    val fabrique = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
    fabrique.newSAXParser().parse(contenu.inputStream(), gestionnaire)
}

/** Les noms d'éléments portent parfois un préfixe (« x:c ») : on l'ignore. */
private fun local(nom: String) = nom.substringAfter(':')

private fun lireChaines(contenu: ByteArray): List<String> {
    val chaines = mutableListOf<String>()
    analyser(contenu, object : DefaultHandler() {
        private var courante: StringBuilder? = null
        private var dansTexte = false
        private var dansPhonetique = false

        override fun startElement(uri: String?, l: String?, nom: String, a: Attributes) {
            when (local(nom)) {
                "si" -> courante = StringBuilder()
                "t" -> dansTexte = true
                "rPh" -> dansPhonetique = true
            }
        }

        override fun endElement(uri: String?, l: String?, nom: String) {
            when (local(nom)) {
                "si" -> { chaines += courante.toString(); courante = null }
                "t" -> dansTexte = false
                "rPh" -> dansPhonetique = false
            }
        }

        override fun characters(ch: CharArray, debut: Int, longueur: Int) {
            if (dansTexte && !dansPhonetique) courante?.appendRange(ch, debut, debut + longueur)
        }
    })
    return chaines
}

private fun lireRelations(contenu: ByteArray): Map<String, String> {
    val relations = HashMap<String, String>()
    analyser(contenu, object : DefaultHandler() {
        override fun startElement(uri: String?, l: String?, nom: String, a: Attributes) {
            if (local(nom) == "Relationship") {
                relations[a.getValue("Id")] = a.getValue("Target")
            }
        }
    })
    return relations
}

private fun lireFeuilles(contenu: ByteArray): List<Pair<String, String>> {
    val feuilles = mutableListOf<Pair<String, String>>()
    analyser(contenu, object : DefaultHandler() {
        override fun startElement(uri: String?, l: String?, nom: String, a: Attributes) {
            if (local(nom) == "sheet") {
                val relation = (0 until a.length)
                    .firstOrNull { local(a.getQName(it)) == "id" && a.getQName(it).contains(':') }
                    ?.let { a.getValue(it) }
                    ?: return
                feuilles += a.getValue("name") to relation
            }
        }
    })
    return feuilles
}

/** « AB12 » → colonne 27 (à partir de 0). */
private fun colonneDe(reference: String): Int {
    var colonne = 0
    for (c in reference) {
        if (!c.isLetter()) break
        colonne = colonne * 26 + (c.uppercaseChar() - 'A' + 1)
    }
    return colonne - 1
}

private fun lireFeuille(contenu: ByteArray, chaines: List<String>): Feuille {
    val cellules = HashMap<Int, HashMap<Int, Any>>()
    analyser(contenu, object : DefaultHandler() {
        private var ligne = -1
        private var colonne = -1
        private var type = ""
        private val texte = StringBuilder()
        private var capter = false
        private var colonneSuivante = 0

        override fun startElement(uri: String?, l: String?, nom: String, a: Attributes) {
            when (local(nom)) {
                "row" -> {
                    ligne = a.getValue("r")?.toIntOrNull()?.minus(1) ?: (ligne + 1)
                    colonneSuivante = 0
                }
                "c" -> {
                    colonne = a.getValue("r")?.let(::colonneDe) ?: colonneSuivante
                    colonneSuivante = colonne + 1
                    type = a.getValue("t") ?: ""
                    texte.setLength(0)
                }
                // <v> : valeur ; <t> dans <is> : texte en ligne. <f> (formule) ignoré.
                "v", "t" -> capter = true
            }
        }

        override fun endElement(uri: String?, l: String?, nom: String) {
            when (local(nom)) {
                "v", "t" -> capter = false
                "c" -> {
                    val brut = texte.toString()
                    val valeur: Any? = when (type) {
                        "s" -> brut.trim().toIntOrNull()?.let { chaines.getOrNull(it) }
                        "str", "inlineStr" -> brut
                        "b" -> brut.trim() == "1"
                        "e" -> null
                        else -> brut.trim().toDoubleOrNull()
                    }
                    if (valeur != null && !(valeur is String && valeur.isEmpty())) {
                        cellules.getOrPut(ligne) { HashMap() }[colonne] = valeur
                    }
                }
            }
        }

        override fun characters(ch: CharArray, debut: Int, longueur: Int) {
            if (capter) texte.appendRange(ch, debut, debut + longueur)
        }
    })
    return Feuille(cellules)
}
