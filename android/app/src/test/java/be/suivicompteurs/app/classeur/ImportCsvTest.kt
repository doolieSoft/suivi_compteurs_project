package be.suivicompteurs.app.classeur

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCsvTest {

    private fun d(iso: String) = LocalDate.parse(iso)

    @Test
    fun `virgules, en-tete et dates ISO`() {
        val l = ImportCsv.lire("date,index\n2024-01-31,1234.5\n2024-02-29,1300\n")
        assertEquals(listOf(d("2024-01-31") to 1234.5, d("2024-02-29") to 1300.0), l.releves)
        assertTrue(l.ignorees.isEmpty())
    }

    @Test
    fun `point-virgule, virgule decimale et dates europeennes`() {
        val l = ImportCsv.lire("\uFEFFDatum;Stand\r\n31/01/2024;1234,5\r\n05/02/2024;1 240,25\r\n")
        assertEquals(listOf(d("2024-01-31") to 1234.5, d("2024-02-05") to 1240.25), l.releves)
    }

    /** Export réel cité dans l'issue #10 : horodatage ISO complet, virgule suivie d'une espace. */
    @Test
    fun `horodatage ISO avec fuseau, comme l'export de l'issue 10`() {
        val l = ImportCsv.lire("Timestamp, Value\n2025-10-12T13:08:31.100+01:00, 24511.0\n2025-11-09T08:12:04.512+01:00, 24718.5\n")
        assertEquals(listOf(d("2025-10-12") to 24511.0, d("2025-11-09") to 24718.5), l.releves)
        assertTrue(l.ignorees.isEmpty())
    }

    @Test
    fun `dates americaines reconnues a un mois au-dela de 12`() {
        val l = ImportCsv.lire("01/31/2024,100\n02/05/2024,110\n")
        assertEquals(listOf(d("2024-01-31") to 100.0, d("2024-02-05") to 110.0), l.releves)
    }

    @Test
    fun `sans indice, le jour vient d'abord`() {
        assertEquals(d("2024-04-03"), ImportCsv.lire("03/04/2024;100").releves.single().first)
    }

    @Test
    fun `heures ignorees, un seul releve par jour, le plus haut`() {
        val l = ImportCsv.lire("2024-01-31 08:00;100\n2024-01-31T20:15:00Z;104\n2024-02-01 07:00;105")
        assertEquals(listOf(d("2024-01-31") to 104.0, d("2024-02-01") to 105.0), l.releves)
        assertEquals(1, l.memeJour)
    }

    @Test
    fun `guillemets, tabulations, unites et colonnes inversees`() {
        assertEquals(1234.5, ImportCsv.lire("\"2024-01-31\",\"1234,5\"").releves.single().second, 1e-9)
        assertEquals(42.0, ImportCsv.lire("2024-01-31\t42 kWh").releves.single().second, 1e-9)
        assertEquals(d("2024-01-31") to 77.0, ImportCsv.lire("77;31.01.2024").releves.single())
    }

    @Test
    fun `separateurs de milliers`() {
        assertEquals(1234.5, ImportCsv.lireNombre("1.234,5")!!, 1e-9)
        assertEquals(1234.5, ImportCsv.lireNombre("1,234.5")!!, 1e-9)
        assertEquals(1234.0, ImportCsv.lireNombre("1 234")!!, 1e-9)
        assertNull(ImportCsv.lireNombre("environ 12"))
        assertNull(ImportCsv.lireNombre(""))
    }

    @Test
    fun `lignes illisibles signalees avec leur numero`() {
        val l = ImportCsv.lire("date;index\n2024-01-31;100\n\n31/02/2024;110\n2024-03-01;?\n")
        assertEquals(1, l.releves.size)
        assertEquals(
            listOf(4 to ImportCsv.Raison.DATE_ILLISIBLE, 5 to ImportCsv.Raison.INDEX_ILLISIBLE),
            l.ignorees.map { it.numero to it.raison },
        )
    }

    @Test
    fun `tri face aux releves du compteur`() {
        val existants = mapOf(d("2024-02-01") to (200.0 to false), d("2024-04-01") to (400.0 to false))
        val tri = ImportCsv.trier(
            listOf(
                d("2024-01-01") to 100.0, // retenu
                d("2024-02-01") to 210.0, // déjà relevé
                d("2024-03-01") to 150.0, // recule par rapport au 1/2
                d("2024-03-15") to 300.0, // retenu
                d("2024-03-20") to 290.0, // recule par rapport au 15/3, retenu juste avant
                d("2024-03-25") to 450.0, // dépasse le 1/4
            ),
            existants,
        )
        assertEquals(listOf(d("2024-01-01") to 100.0, d("2024-03-15") to 300.0), tri.retenus)
        assertEquals(listOf(d("2024-02-01")), tri.dejaReleves)
        assertEquals(listOf(d("2024-03-01"), d("2024-03-20"), d("2024-03-25")), tri.incoherents.map { it.first })
    }

    @Test
    fun `un tour complet apres autorise un index plus haut`() {
        val tri = ImportCsv.trier(listOf(d("2024-01-01") to 99_990.0), mapOf(d("2024-02-01") to (50.0 to true)))
        assertEquals(1, tri.retenus.size)
    }
}
