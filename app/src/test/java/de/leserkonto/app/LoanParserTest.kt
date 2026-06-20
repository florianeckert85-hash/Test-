package de.leserkonto.app

import de.leserkonto.app.data.opac.LoanParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanParserTest {

    @Test
    fun `parses table-based loans with title author due date and renewability`() {
        val html = """
            <html><body>
            <a href="/wehr/Logout">Abmelden</a>
            <table>
              <tr><th>Titel</th><th>Verfasser</th><th>Entliehen</th><th>Fällig am</th><th></th></tr>
              <tr>
                <td><a href="/wehr/recherche/detail/123">Der Vorleser</a></td>
                <td class="author">Schlink, Bernhard</td>
                <td>01.06.2026</td>
                <td class="faellig">15.07.2026</td>
                <td><input type="checkbox" name="renew" value="123"></td>
              </tr>
              <tr>
                <td><a href="/wehr/recherche/detail/999">Die Verwandlung</a></td>
                <td class="author">Kafka, Franz</td>
                <td>02.06.2026</td>
                <td class="faellig">20.07.2026</td>
                <td>nicht verlängerbar (vorgemerkt)</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val loans = LoanParser.parse(Jsoup.parse(html, "https://bibliothek.komm.one/wehr/Leserkonto"))

        assertEquals(2, loans.size)

        val first = loans[0]
        assertEquals("Der Vorleser", first.title)
        assertEquals("Schlink, Bernhard", first.author)
        assertEquals(LocalDate.of(2026, 7, 15), first.dueDate)
        assertTrue("Item with a checkbox should be renewable", first.renewable)
        assertEquals("123", first.id)

        val second = loans[1]
        assertEquals("Die Verwandlung", second.title)
        assertEquals(LocalDate.of(2026, 7, 20), second.dueDate)
        assertTrue("Reserved item must not be renewable", !second.renewable)
    }

    @Test
    fun `picks the latest date as due date when several are present and unlabelled`() {
        val html = """
            <table><tr>
              <td><a href="/detail/1">Titel X</a></td>
              <td>10.05.2026</td>
              <td>30.06.2026</td>
            </tr></table>
        """.trimIndent()
        val loan = LoanParser.parse(Jsoup.parse(html, "https://x/")).single()
        assertEquals(LocalDate.of(2026, 6, 30), loan.dueDate)
    }

    @Test
    fun `parses card-based layout`() {
        val html = """
            <html><body>
            <div class="account-item">
              <span class="titel">Sapiens</span>
              <span class="author">Harari, Yuval Noah</span>
              <span class="faellig">Fällig am 12.08.2026</span>
              <button>Verlängern</button>
            </div>
            </body></html>
        """.trimIndent()
        val loans = LoanParser.parse(Jsoup.parse(html, "https://x/"))
        val loan = loans.firstOrNull { it.title == "Sapiens" }
        assertNotNull("Card layout should yield a loan", loan)
        assertEquals(LocalDate.of(2026, 8, 12), loan!!.dueDate)
        assertTrue(loan.renewable)
    }
}
