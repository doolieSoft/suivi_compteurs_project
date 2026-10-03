package be.suivicompteurs.app.reseau

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture de la réponse du géocodage d'Open-Meteo, telle qu'elle arrive. */
class GeocodageTest {

    @Test
    fun `les lieux trouves sont lus avec region et pays`() {
        val corps = """
            {"results":[
              {"id":2792413,"name":"Liège","latitude":50.63373,"longitude":5.56749,
               "country_code":"BE","admin1":"Wallonie","country":"Belgique"},
              {"id":2792414,"name":"Liège","latitude":50.5,"longitude":5.5,"country":"Belgique"}
            ],"generationtime_ms":0.5}
        """.trimIndent()
        val lieux = OpenMeteo.lireLieux(corps)
        assertEquals(2, lieux.size)
        assertEquals("Liège, Wallonie, Belgique", lieux[0].libelle)
        assertEquals(50.63373, lieux[0].latitude, 1e-9)
        assertEquals(5.56749, lieux[0].longitude, 1e-9)
        assertEquals("Liège, Belgique", lieux[1].libelle)
    }

    @Test
    fun `un nom inconnu ne rend aucun lieu`() {
        assertTrue(OpenMeteo.lireLieux("""{"generationtime_ms":0.3}""").isEmpty())
    }
}
