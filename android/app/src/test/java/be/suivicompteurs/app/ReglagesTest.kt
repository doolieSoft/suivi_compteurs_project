package be.suivicompteurs.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'adresse du serveur est saisie au clavier d'un téléphone, souvent à la hâte.
 * Ces cas couvrent les deux destinations réelles : le PC du réseau domestique
 * et l'hébergeur en ligne, qui n'appellent pas les mêmes valeurs par défaut.
 */
class ReglagesTest {

    @Test
    fun `une adresse IP nue vise le serveur domestique`() {
        assertEquals(
            "http://192.168.0.10:8000",
            Reglages.normaliser("192.168.0.10"),
        )
    }

    @Test
    fun `un nom de domaine nu vise un hebergeur en HTTPS`() {
        // Le piège : y ajouter le port 8000 rendrait l'adresse injoignable.
        assertEquals(
            "https://stefano.pythonanywhere.com",
            Reglages.normaliser("stefano.pythonanywhere.com"),
        )
    }

    @Test
    fun `un schema explicite est respecte sans ajout`() {
        assertEquals(
            "https://stefano.pythonanywhere.com",
            Reglages.normaliser("https://stefano.pythonanywhere.com"),
        )
        assertEquals(
            "http://192.168.0.10:8000",
            Reglages.normaliser("http://192.168.0.10:8000"),
        )
        assertEquals("http://192.168.0.10", Reglages.normaliser("http://192.168.0.10"))
    }

    @Test
    fun `un port precise n'est jamais remplace`() {
        assertEquals("http://192.168.0.10:9000", Reglages.normaliser("192.168.0.10:9000"))
    }

    @Test
    fun `la barre oblique finale et les espaces sont retires`() {
        assertEquals(
            "https://stefano.pythonanywhere.com",
            Reglages.normaliser("  https://stefano.pythonanywhere.com/  "),
        )
    }

    @Test
    fun `un nom de machine local garde le port du serveur de developpement`() {
        assertEquals("http://portable.local:8000", Reglages.normaliser("portable.local"))
    }

    @Test
    fun `une saisie vide reste vide`() {
        assertEquals("", Reglages.normaliser(""))
        assertEquals("", Reglages.normaliser("   "))
    }
}
