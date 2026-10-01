package be.suivicompteurs.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du choix de l'index parmi ce que la reconnaissance a lu.
 *
 * Les jeux de lignes reproduisent ce qu'une photo de compteur donne vraiment :
 * l'index noyé parmi un numéro de série, une année, un numéro d'agrément.
 */
class SelecteurIndexTest {

    private val indexGazPrecedent = 24978.737
    private val decimalesGaz = 3

    @Test
    fun `retient l'index et ignore numero de serie et annee`() {
        val lignes = listOf(
            "G4",
            "24981 625",
            "N° 0815 4432 219",
            "2011",
            "m3",
        )
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, decimalesGaz)
        assertNotNull(proposition)
        assertEquals(24981.625, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }

    @Test
    fun `recompose un index reparti sur deux lignes`() {
        // Chiffres noirs sur une ligne, décimales rouges sur la suivante.
        val lignes = listOf("24981", "625")
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, decimalesGaz)
        assertNotNull(proposition)
        assertEquals(24981.625, proposition!!.valeur, 0.001)
    }

    @Test
    fun `un numero de serie seul ne passe pas pour un index fiable`() {
        // Piège réel : « 0815 4432 » se lit 81 544,32 avec une virgule, donc
        // au-dessus du dernier index. Seule la vraisemblance de la progression
        // permet de l'écarter — la comparaison brute ne suffit pas.
        val lignes = listOf("0815 4432")
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, decimalesGaz)
        assertNotNull(proposition)
        assertTrue(
            "une lecture invraisemblable doit être signalée",
            proposition!!.confiance <= 0.3,
        )
        assertTrue(proposition.explication.contains("ressemble à l'index"))
    }

    @Test
    fun `signale une lecture entierement inferieure au dernier index`() {
        // Les décimales rouges ont échappé à la lecture : quelle que soit la
        // position de la virgule, le résultat reste sous le dernier index.
        val lignes = listOf("24978")
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, decimalesGaz)
        assertNotNull(proposition)
        assertTrue(proposition!!.confiance < 0.2)
        assertTrue(proposition.explication.contains("corrigez", ignoreCase = true))
    }

    @Test
    fun `tolere une forte progression sur un compteur fraichement pose`() {
        // Compteur d'eau remplacé, reparti de 21,5 : une progression de 40 m³
        // est normale, alors qu'elle dépasse largement la moitié de l'index.
        val proposition = SelecteurIndex.choisir(listOf("00061500"), 21.5, 3)
        assertNotNull(proposition)
        assertEquals(61.5, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }

    @Test
    fun `signale une progression invraisemblable`() {
        // Un chiffre lu en trop multiplie l'index par dix.
        val lignes = listOf("249817")
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, 0)
        assertNotNull(proposition)
        assertTrue(proposition!!.valeur > indexGazPrecedent * 1.5)
        assertTrue(proposition.confiance <= 0.4)
        // Six chiffres entiers au lieu de cinq : ce n'est pas un index de ce
        // compteur, et c'est plus juste à dire qu'une progression inhabituelle.
        assertTrue(proposition.explication.contains("ressemble à l'index"))
    }

    @Test
    fun `choisit la plus petite lecture qui ne recule pas`() {
        // Deux candidats plausibles : le plus proche gagne, car un compteur
        // progresse peu entre deux relevés.
        val lignes = listOf("24979", "31000")
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, 0)
        assertNotNull(proposition)
        assertEquals(24979.0, proposition!!.valeur, 0.001)
    }

    @Test
    fun `gere un compteur electrique sans decimale`() {
        val lignes = listOf("40 512", "kWh", "HP")
        val proposition = SelecteurIndex.choisir(lignes, 40314.0, 1)
        assertNotNull(proposition)
        assertEquals(40512.0, proposition!!.valeur, 0.001)
    }

    @Test
    fun `renvoie null quand aucun chiffre n'est lisible`() {
        assertNull(SelecteurIndex.choisir(listOf("m3", "GAZ", "---"), indexGazPrecedent, 3))
        assertNull(SelecteurIndex.choisir(emptyList(), indexGazPrecedent, 3))
    }

    @Test
    fun `fonctionne sans historique, en l'avouant`() {
        val proposition = SelecteurIndex.choisir(listOf("00123", "456"), null, 3)
        assertNotNull(proposition)
        assertTrue(proposition!!.confiance <= 0.3)
        assertTrue(proposition.explication.contains("Aucun relevé antérieur"))
    }

    @Test
    fun `ignore les suites trop courtes pour etre un index`() {
        // « 12 » ou « 4 » sont des fragments, pas des index.
        val suites = SelecteurIndex.suitesDeChiffres(listOf("G4", "12", "24981"))
        assertTrue(suites.contains("24981"))
        assertTrue(suites.none { it.length < 3 })
    }

    @Test
    fun `tolere les separateurs imprimes sur le compteur`() {
        val lignes = listOf("24.981,625")
        val proposition = SelecteurIndex.choisir(lignes, indexGazPrecedent, decimalesGaz)
        assertNotNull(proposition)
        assertEquals(24981.625, proposition!!.valeur, 0.001)
    }

    @Test
    fun `un index identique au precedent est accepte sans alarme`() {
        // Relevé rapproché sur un compteur à l'arrêt : progression nulle.
        val lignes = listOf("24978737")
        val proposition = SelecteurIndex.choisir(lignes, gazLiersonsExact(), decimalesGaz)
        assertNotNull(proposition)
        assertEquals(24978.737, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }

    private fun gazLiersonsExact() = 24978.737
}

/**
 * Cas reconstitués d'après la photo d'un compteur gaz réel :
 * un Elster BK-G6 de 2010, dont la plaque porte un numéro de série en gros
 * caractères à côté d'un code-barres, un numéro d'agrément, un millésime et
 * plusieurs débits — autant de suites de chiffres parmi lesquelles l'index
 * n'est ni la plus longue, ni la plus visible.
 */
class SelecteurIndexCompteurReelTest {

    /** Ce que la reconnaissance de texte voit sur la plaque, dans le désordre. */
    private val plaqueBkG6 = listOf(
        "elster",
        "BK-G6",
        "G6  2010",
        "Qmax 10 m³/h",
        "D087",
        "Qmin 0,06 m³/h",
        "122.43",
        "V 4 dm³",
        "Pmax 0,2bar",
        "cogegaz",
        "27364770",                       // numéro de série, près du code-barres
        "S3 Pmax T2 = 100 mbar",
        "DIN EN 1359:2007",
        "2 4 9 7 8 , 9 7 5",              // l'index : chiffres noirs puis rouges
        "m3",
        "D02527364770   1 imp = 0,01m³",
    )

    @Test
    fun `retient l'index et non le numero de serie`() {
        // Le piège : « 27364770 » compte huit chiffres contre sept pour
        // l'index, et se lit 27 364,770 — soit une progression plausible en
        // apparence. Seule la partie entière le démasque : 27364 contre 24978.
        val proposition = SelecteurIndex.choisir(plaqueBkG6, 24978.737, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
        assertTrue(
            "une lecture cohérente doit inspirer confiance",
            proposition.confiance >= 0.8,
        )
    }

    @Test
    fun `resiste a l'ordre dans lequel les lignes sont lues`() {
        // ML Kit ne garantit pas l'ordre : le résultat ne doit pas en dépendre.
        val proposition = SelecteurIndex.choisir(plaqueBkG6.reversed(), 24978.737, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
    }

    @Test
    fun `suit le compteur quand la partie entiere avance`() {
        // Relevé suivant : les chiffres noirs sont passés à 24979.
        val lignes = plaqueBkG6.map { if (it.startsWith("2 4 9 7 8")) "24979,120" else it }
        val proposition = SelecteurIndex.choisir(lignes, 24978.975, 3)
        assertNotNull(proposition)
        assertEquals(24979.120, proposition!!.valeur, 0.001)
    }

    @Test
    fun `ne se laisse pas prendre par le millesime ni par l'agrement`() {
        // « 2010 », « 122.43 », « 1359:2007 » : tous sous le dernier index une
        // fois la virgule posée, ou bien de partie entière plus grande.
        val proposition = SelecteurIndex.choisir(plaqueBkG6, 24978.737, 3)
        assertNotNull(proposition)
        assertTrue(proposition!!.brut.startsWith("24978"))
    }

    @Test
    fun `le numero de serie seul ne devient pas un index credible`() {
        // Si les chiffres de l'index échappent à la lecture, ce qui reste ne
        // doit surtout pas passer pour un relevé valide.
        val sansIndex = plaqueBkG6.filterNot { it.startsWith("2 4 9 7 8") }
        // Trente jours à 13,2 m³/jour, marge doublée : environ 800 m³. Le
        // numéro de série en exigerait 2 386.
        val proposition = SelecteurIndex.choisir(sansIndex, 24978.737, 3, 800.0)
        assertNotNull(proposition)
        assertTrue(
            "une progression de plusieurs milliers de m³ doit être signalée",
            proposition!!.confiance <= 0.3,
        )
        // Le motif retenu est le bon : « 27364 » ne partage qu'un chiffre de
        // tête avec « 24978 ». C'est plus précis que de parler de progression
        // inhabituelle, qui laisserait croire que le compteur a bougé.
        assertTrue(proposition.explication.contains("ressemble à l'index"))
    }
}


/**
 * Cas signalé après un essai réel : l'application a proposé un nombre tiré de
 * la mention « D02527364770 » imprimée sous le cadran, parce que la
 * reconnaissance n'avait pas lu la ligne de l'index.
 *
 * Le remède n'est pas de mieux départager les candidats — aucun n'était bon —
 * mais de reconnaître qu'aucun ne ressemble à un index, et de le dire.
 */
class SelecteurIndexIndexIllisibleTest {

    private val dernierIndex = 24978.737

    /** La plaque sans la ligne de l'index : ce que voit l'OCR quand il échoue. */
    private val plaqueSansIndex = listOf(
        "elster",
        "BK-G6",
        "G6  2010",
        "Qmax 10 m³/h",
        "D087",
        "Qmin 0,06 m³/h",
        "122.43",
        "27364770",
        "S3 Pmax T2 = 100 mbar",
        "DIN EN 1359:2007",
        "m3",
        "D02527364770   1 imp = 0,01m³",
    )

    @Test
    fun `n'invente pas un index a partir de la mention sous le cadran`() {
        val proposition = SelecteurIndex.choisir(plaqueSansIndex, dernierIndex, 3)
        assertNotNull(proposition)
        assertTrue(
            "la confiance doit être au plancher quand rien ne ressemble à un index",
            proposition!!.confiance <= 0.2,
        )
        assertTrue(
            "le message doit inviter à saisir à la main, pas suggérer une valeur",
            proposition.explication.contains("saisissez-le à la main"),
        )
    }

    @Test
    fun `le numero de serie ne gagne pas non plus`() {
        // « 27364770 » a pourtant cinq chiffres entiers une fois la virgule
        // posée — comme l'index. Seuls les chiffres de tête le trahissent.
        val proposition = SelecteurIndex.choisir(plaqueSansIndex, dernierIndex, 3)
        assertNotNull(proposition)
        assertTrue(proposition!!.confiance <= 0.2)
    }

    @Test
    fun `mais retrouve l'index des qu'il est lisible`() {
        val avecIndex = plaqueSansIndex + "24978,975"
        val proposition = SelecteurIndex.choisir(avecIndex, dernierIndex, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }

    @Test
    fun `tolere que l'OCR espace les chiffres du cadran`() {
        // Les tambours sont séparés : la reconnaissance rend souvent « 2 4 9 7 8 ».
        val avecIndex = plaqueSansIndex + "2 4 9 7 8 , 9 7 5"
        val proposition = SelecteurIndex.choisir(avecIndex, dernierIndex, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
    }

    @Test
    fun `accepte le passage a la dizaine superieure`() {
        // 24978 -> 24980 : trois chiffres de tête communs, cela reste l'index.
        val avecIndex = plaqueSansIndex + "24980,100"
        val proposition = SelecteurIndex.choisir(avecIndex, dernierIndex, 3)
        assertNotNull(proposition)
        assertEquals(24980.100, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }
}

/**
 * Lecture d'un cadran isolé, tel qu'il parvient après un entourage au doigt.
 *
 * C'est le cas que la sélection de zone rend courant : plus de numéro de série
 * ni de millésime, mais la question de la virgule reste entière — les tambours
 * ne l'impriment pas.
 */
class SelecteurIndexCadranIsoleTest {

    private val precedent = 24978.737

    @Test
    fun `place la virgule d'apres le nombre de chiffres du compteur`() {
        // Cinq chiffres noirs et trois rouges : « 24978975 » ne peut valoir
        // que 24 978,975.
        val proposition = SelecteurIndex.choisir(listOf("24978975"), precedent, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }

    @Test
    fun `tolere que les tambours soient lus separement`() {
        // Le cadre rouge sépare souvent les décimales du reste.
        val proposition = SelecteurIndex.choisir(listOf("24978", "975"), precedent, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
    }

    @Test
    fun `se rabat sur le compte des chiffres entiers si une decimale manque`() {
        // Un tambour à mi-course, et il n'en reste que deux de lisibles.
        val proposition = SelecteurIndex.choisir(listOf("2497897"), precedent, 3)
        assertNotNull(proposition)
        assertEquals(24978.97, proposition!!.valeur, 0.001)
    }

    @Test
    fun `n'est pas trompe par un zero de tete sur le cadran`() {
        // Certains compteurs affichent le zéro de poids fort.
        val proposition = SelecteurIndex.choisir(listOf("024978975"), precedent, 3)
        assertNotNull(proposition)
        assertEquals(24978.975, proposition!!.valeur, 0.001)
    }

    @Test
    fun `fonctionne sur un compteur electrique sans decimale`() {
        val proposition = SelecteurIndex.choisir(listOf("405127"), 40314.0, 1)
        assertNotNull(proposition)
        assertEquals(40512.7, proposition!!.valeur, 0.001)
    }
}
