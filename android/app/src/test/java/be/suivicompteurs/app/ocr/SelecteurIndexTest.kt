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

    private val gazLiserons = 24978.737
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
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, decimalesGaz)
        assertNotNull(proposition)
        assertEquals(24981.625, proposition!!.valeur, 0.001)
        assertTrue(proposition.confiance >= 0.8)
    }

    @Test
    fun `recompose un index reparti sur deux lignes`() {
        // Chiffres noirs sur une ligne, décimales rouges sur la suivante.
        val lignes = listOf("24981", "625")
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, decimalesGaz)
        assertNotNull(proposition)
        assertEquals(24981.625, proposition!!.valeur, 0.001)
    }

    @Test
    fun `un numero de serie seul ne passe pas pour un index fiable`() {
        // Piège réel : « 0815 4432 » se lit 81 544,32 avec une virgule, donc
        // au-dessus du dernier index. Seule la vraisemblance de la progression
        // permet de l'écarter — la comparaison brute ne suffit pas.
        val lignes = listOf("0815 4432")
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, decimalesGaz)
        assertNotNull(proposition)
        assertTrue(
            "une lecture invraisemblable doit être signalée",
            proposition!!.confiance <= 0.3,
        )
        assertTrue(proposition.explication.contains("inhabituelle"))
    }

    @Test
    fun `signale une lecture entierement inferieure au dernier index`() {
        // Les décimales rouges ont échappé à la lecture : quelle que soit la
        // position de la virgule, le résultat reste sous le dernier index.
        val lignes = listOf("24978")
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, decimalesGaz)
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
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, 0)
        assertNotNull(proposition)
        assertTrue(proposition!!.valeur > gazLiserons * 1.5)
        assertTrue(proposition.confiance <= 0.4)
        assertTrue(proposition.explication.contains("inhabituelle"))
    }

    @Test
    fun `choisit la plus petite lecture qui ne recule pas`() {
        // Deux candidats plausibles : le plus proche gagne, car un compteur
        // progresse peu entre deux relevés.
        val lignes = listOf("24979", "31000")
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, 0)
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
        assertNull(SelecteurIndex.choisir(listOf("m3", "GAZ", "---"), gazLiserons, 3))
        assertNull(SelecteurIndex.choisir(emptyList(), gazLiserons, 3))
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
        val proposition = SelecteurIndex.choisir(lignes, gazLiserons, decimalesGaz)
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
