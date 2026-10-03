package be.suivicompteurs.app.classeur

import java.io.OutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Écriture minimale d'un classeur Excel (.xlsx), pendant de [Classeur].
 *
 * Juste ce qu'il faut pour un export lisible et réimportable : textes,
 * nombres, dates, formules, en-têtes en gras, largeurs de colonnes et volets
 * figés. Excel, LibreOffice et Google Sheets recalculent les formules à
 * l'ouverture.
 */
class EcrivainXlsx {

    /** Format de cellule : chaque valeur correspond à un style de styles.xml. */
    enum class Format(internal val style: Int) {
        STANDARD(0), GRAS(1), TITRE(2), DATE(3),
        DECIMALES_0(4), DECIMALES_1(5), DECIMALES_2(6), DECIMALES_3(7), DECIMALES_5(8),
    }

    /** Formule Excel, sans le signe « = » initial. */
    data class Formule(val expression: String)

    class Cellule(val valeur: Any?, val format: Format = Format.STANDARD)

    inner class FeuilleEcrite(val nom: String) {
        internal val lignes = mutableListOf<List<Cellule?>>()
        internal var largeurs: List<Int> = emptyList()
        internal var figerPremiereLigne = false

        fun ligne(vararg cellules: Cellule?) {
            lignes += cellules.toList()
        }

        fun ligneVide() {
            lignes += emptyList<Cellule?>()
        }

        /** Rang (à partir de 1) de la prochaine ligne écrite, pour les formules. */
        val prochaineLigne: Int get() = lignes.size + 1

        fun largeurs(vararg valeurs: Int) {
            largeurs = valeurs.toList()
        }

        fun figerEnTete() {
            figerPremiereLigne = true
        }
    }

    private val feuilles = mutableListOf<FeuilleEcrite>()

    fun feuille(nom: String): FeuilleEcrite = FeuilleEcrite(nom).also { feuilles += it }

    fun ecrire(sortie: OutputStream) {
        ZipOutputStream(sortie).use { zip ->
            fun fichier(chemin: String, contenu: String) {
                zip.putNextEntry(ZipEntry(chemin))
                zip.write(contenu.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fichier("[Content_Types].xml", typesDeContenu())
            fichier("_rels/.rels", RELATIONS_RACINE)
            fichier("xl/workbook.xml", classeur())
            fichier("xl/_rels/workbook.xml.rels", relationsClasseur())
            fichier("xl/styles.xml", STYLES)
            feuilles.forEachIndexed { i, f -> fichier("xl/worksheets/sheet${i + 1}.xml", feuille(f)) }
        }
    }

    // -- fichiers XML -----------------------------------------------------------

    private fun typesDeContenu() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        feuilles.indices.forEach {
            append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun classeur() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("<sheets>")
        feuilles.forEachIndexed { i, f ->
            append("""<sheet name="${echapper(f.nom.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets>")
        // Sans valeurs mises en cache, on demande le calcul des formules à l'ouverture.
        append("""<calcPr fullCalcOnLoad="1"/>""")
        append("</workbook>")
    }

    private fun relationsClasseur() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        feuilles.indices.forEach {
            append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
        }
        val styles = feuilles.size + 1
        append("""<Relationship Id="rId$styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun feuille(f: FeuilleEcrite) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (f.figerPremiereLigne) {
            append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        }
        if (f.largeurs.isNotEmpty()) {
            append("<cols>")
            f.largeurs.forEachIndexed { i, l ->
                append("""<col min="${i + 1}" max="${i + 1}" width="$l" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        f.lignes.forEachIndexed { i, cellules ->
            val rang = i + 1
            if (cellules.isEmpty()) return@forEachIndexed
            append("""<row r="$rang">""")
            cellules.forEachIndexed { j, c ->
                if (c == null || c.valeur == null) return@forEachIndexed
                val reference = "${colonne(j)}$rang"
                val style = if (c.format.style != 0) """ s="${c.format.style}"""" else ""
                when (val v = c.valeur) {
                    is Formule -> append("""<c r="$reference"$style><f>${echapper(v.expression)}</f></c>""")
                    is LocalDate -> append("""<c r="$reference" s="${maxOf(c.format.style, Format.DATE.style)}"><v>${serie(v)}</v></c>""")
                    is Number -> append("""<c r="$reference"$style><v>${v.toDouble().let(::nombre)}</v></c>""")
                    is Boolean -> append("""<c r="$reference" t="b"$style><v>${if (v) 1 else 0}</v></c>""")
                    else -> {
                        val texte = v.toString()
                        if (texte.isNotEmpty()) {
                            append("""<c r="$reference" t="inlineStr"$style><is><t xml:space="preserve">${echapper(texte)}</t></is></c>""")
                        }
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun nombre(x: Double): String =
        if (x % 1.0 == 0.0 && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString()

    companion object {
        private val ORIGINE: LocalDate = LocalDate.of(1899, 12, 30)

        internal fun serie(jour: LocalDate): Long = ChronoUnit.DAYS.between(ORIGINE, jour)

        /** 0 → A, 25 → Z, 26 → AA. */
        internal fun colonne(rang: Int): String {
            var n = rang + 1
            val lettres = StringBuilder()
            while (n > 0) {
                val reste = (n - 1) % 26
                lettres.insert(0, 'A' + reste)
                n = (n - 1) / 26
            }
            return lettres.toString()
        }

        private fun echapper(texte: String) = buildString {
            for (c in texte) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    // Les caractères de contrôle rendraient le fichier illisible.
                    else -> if (c >= ' ' || c == '\n' || c == '\t') append(c)
                }
            }
        }

        private const val RELATIONS_RACINE =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                """</Relationships>"""

        /** Dans l'ordre de [Format] : standard, gras, titre, date, puis décimales 0, 1, 2, 3, 5. */
        private const val STYLES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<numFmts count="5">""" +
                """<numFmt numFmtId="164" formatCode="dd/mm/yyyy"/>""" +
                """<numFmt numFmtId="165" formatCode="0.0"/>""" +
                """<numFmt numFmtId="166" formatCode="0.000"/>""" +
                """<numFmt numFmtId="167" formatCode="0.00000"/>""" +
                """<numFmt numFmtId="168" formatCode="0.00"/>""" +
                """</numFmts>""" +
                """<fonts count="3">""" +
                """<font><sz val="11"/><name val="Calibri"/></font>""" +
                """<font><b/><sz val="11"/><name val="Calibri"/></font>""" +
                """<font><b/><sz val="12"/><name val="Calibri"/></font>""" +
                """</fonts>""" +
                """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
                """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                """<cellXfs count="9">""" +
                """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
                """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
                """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
                """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
                """<xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
                """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
                """<xf numFmtId="168" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
                """<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
                """<xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
                """</cellXfs>""" +
                """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
                """</styleSheet>"""
    }
}
