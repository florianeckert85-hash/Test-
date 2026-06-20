package de.leserkonto.app.data.opac

import de.leserkonto.app.data.model.Loan
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure (no-Android, no-network) parsing of a reader-account HTML page into a
 * list of [Loan]s. Kept separate from the HTTP client so it can be unit-tested
 * on the JVM with captured HTML fixtures.
 *
 * The parser is deliberately *heuristic* rather than tied to a single fixed
 * markup: BIBLIOTHECA / Komm.ONE templates differ slightly per library and
 * change over time. The strategy:
 *
 *  1. Find every "row" (table row or repeated card) that contains a German
 *     date (dd.mm.yyyy) — a borrowed item always shows a due date.
 *  2. From each row, extract title, author, due date and whether a renewal
 *     control is present.
 *
 * If a particular library renders something this misses, use the in-app
 * "HTML exportieren" button and adjust the selectors in [extractTitle] etc.
 */
object LoanParser {

    private val DATE_REGEX = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2,4})""")

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN),
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN),
        DateTimeFormatter.ofPattern("d.M.yy", Locale.GERMAN),
    )

    /** Words that, when present near a control, indicate a renewal action. */
    private val RENEW_WORDS = listOf("verläng", "verlaeng", "renew")

    fun parse(doc: Document): List<Loan> {
        val rows = candidateRows(doc)
        return rows.mapNotNull { parseRow(it) }
            // De-duplicate: nested containers can both match the date test.
            .distinctBy { (it.title to it.dueDate) }
    }

    /**
     * Collect candidate "row" elements. Prefer real table rows; if the layout
     * is card-based, fall back to the smallest block elements that contain a date.
     */
    private fun candidateRows(doc: Document): List<Element> {
        val tableRows = doc.select("tr").filter { it.containsDate() && it.select("th").isEmpty() }
        if (tableRows.isNotEmpty()) return tableRows

        // Card / list layout: take leaf-ish blocks that contain a date but whose
        // children don't individually contain *the same* amount of detail.
        val blocks = doc.select(
            "li, div.account-item, div.loan, div.medium, div.row, article, div[class*=item]"
        ).filter { it.containsDate() }

        // Keep only the innermost matching blocks to avoid counting an item twice.
        return blocks.filter { block ->
            block.children().none { child -> child.containsDate() && child.text().length > 20 }
        }.ifEmpty { blocks }
    }

    private fun Element.containsDate(): Boolean = DATE_REGEX.containsMatchIn(this.text())

    private fun parseRow(row: Element): Loan? {
        val text = row.text()
        if (text.isBlank()) return null

        val dueDate = extractDueDate(row) ?: return null
        val title = extractTitle(row) ?: return null
        if (title.isBlank()) return null

        val author = extractAuthor(row)
        val mediaType = extractMediaType(row)
        val renewable = isRenewable(row)
        val id = extractRenewId(row)
        val renewalsRemaining = extractRenewalsRemaining(text)
        val status = extractStatus(row)

        return Loan(
            id = id,
            title = title,
            author = author,
            mediaType = mediaType,
            dueDate = dueDate,
            renewable = renewable,
            renewalsRemaining = renewalsRemaining,
            status = status,
            branch = null,
        )
    }

    /** A row can contain several dates (loan date + due date). The due date is the latest. */
    private fun extractDueDate(row: Element): LocalDate? {
        // Prefer a cell/element explicitly labelled as due date.
        val labelled = row.select(
            "[class*=faellig], [class*=fällig], [class*=due], [class*=rueckgabe], [class*=rückgabe], td.date"
        ).mapNotNull { parseFirstDate(it.text()) }
        if (labelled.isNotEmpty()) return labelled.max()

        val all = DATE_REGEX.findAll(row.text()).mapNotNull { parseDate(it.value) }.toList()
        return all.maxOrNull()
    }

    private fun extractTitle(row: Element): String? {
        // 1) A link to a detail page is almost always the title.
        val link = row.select("a[href]").firstOrNull {
            val h = it.attr("href").lowercase()
            (h.contains("detail") || h.contains("medium") || h.contains("titel") ||
                h.contains("katalog") || h.contains("recherche")) && it.text().isNotBlank()
        }
        if (link != null) return clean(link.text())

        // 2) An element explicitly classed as a title.
        val titled = row.select("[class*=title], [class*=titel], strong, b, h1, h2, h3, h4")
            .firstOrNull { it.text().isNotBlank() }
        if (titled != null) return clean(titled.text())

        // 3) Fallback: the longest non-date text cell.
        val cell = row.select("td, span, div")
            .map { it.ownText() }
            .filter { it.isNotBlank() && !DATE_REGEX.containsMatchIn(it) }
            .maxByOrNull { it.length }
        return cell?.let { clean(it) }
    }

    private fun extractAuthor(row: Element): String? {
        val a = row.select("[class*=author], [class*=verfasser], [class*=urheber]")
            .firstOrNull { it.text().isNotBlank() }
        return a?.let { clean(it.text()) }
    }

    private fun extractMediaType(row: Element): String? {
        val t = row.select("[class*=mediatype], [class*=medientyp], [class*=mediaclass], img[alt]")
            .firstOrNull { (it.attr("alt").ifBlank { it.text() }).isNotBlank() }
        return t?.let { clean(it.attr("alt").ifBlank { it.text() }) }
    }

    private fun isRenewable(row: Element): Boolean {
        if (row.select("input[type=checkbox]").isNotEmpty()) return true
        val lower = row.text().lowercase()
        if (RENEW_WORDS.any { lower.contains(it) }) {
            // Exclude rows that say it can NOT be renewed.
            val negated = listOf("nicht verläng", "keine verläng", "nicht möglich")
            return negated.none { lower.contains(it) }
        }
        // A button/link inside the row that triggers a renewal.
        return row.select("button, a, input[type=submit]").any {
            val label = (it.text() + " " + it.attr("value") + " " + it.attr("title")).lowercase()
            RENEW_WORDS.any { w -> label.contains(w) }
        }
    }

    /** Identifier we can later use to renew exactly this item. */
    private fun extractRenewId(row: Element): String? {
        row.select("input[type=checkbox][value]").firstOrNull()?.let {
            return it.attr("value").ifBlank { it.attr("name") }.ifBlank { null }
        }
        row.select("[data-id], [data-medium], [data-loan-id]").firstOrNull()?.let { el ->
            return listOf("data-id", "data-medium", "data-loan-id")
                .map { el.attr(it) }.firstOrNull { it.isNotBlank() }
        }
        return null
    }

    private fun extractRenewalsRemaining(text: String): Int? {
        // e.g. "noch 2 Verlängerungen", "2x verlängerbar"
        val m = Regex("""(\d+)\s*[x×]?\s*(?:verläng|verlaeng)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractStatus(row: Element): String? {
        val s = row.select("[class*=status], [class*=state]").firstOrNull { it.text().isNotBlank() }
        return s?.let { clean(it.text()) }
    }

    private fun parseFirstDate(text: String): LocalDate? =
        DATE_REGEX.find(text)?.value?.let { parseDate(it) }

    private fun parseDate(raw: String): LocalDate? {
        val m = DATE_REGEX.find(raw) ?: return null
        var (d, mo, y) = m.destructured
        if (y.length == 2) y = "20$y"
        val normalized = "$d.$mo.$y"
        for (fmt in DATE_FORMATS) {
            try {
                return LocalDate.parse(normalized, fmt)
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun clean(s: String): String =
        s.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
}
