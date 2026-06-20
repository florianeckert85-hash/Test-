package de.leserkonto.app.data.opac

import de.leserkonto.app.data.model.AccountData
import de.leserkonto.app.data.model.LibraryConfig
import de.leserkonto.app.data.model.Loan
import de.leserkonto.app.data.model.OpacResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.FormElement
import java.util.concurrent.TimeUnit

/**
 * OPAC client for the Komm.ONE / BIBLIOTHECA web catalogue
 * (e.g. https://bibliothek.komm.one/wehr/Leserkonto).
 *
 * There is no public API, so this logs in like a browser and scrapes the
 * reader-account page. Login-form and renewal-control detection are heuristic
 * (see [findLoginForm] / [renewAll]) so the same code works across the slightly
 * different per-library templates.
 */
class BibliothecaOpenClient(
    private val config: LibraryConfig = LibraryConfig(),
) : OpacClient {

    // Per-client in-memory cookie store keeps the logged-in session.
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                    cookies.forEach { c -> removeAll { it.name == c.name }; add(c) }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host]?.filter { it.matches(url) } ?: emptyList()
        })
        .build()

    // The site rejects non-browser clients (HTTP 403), so we present as one.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8",
    )

    // ---------------------------------------------------------------- public API

    override suspend fun login(username: String, password: String): OpacResult<Unit> =
        runCatchingIo {
            val doc = getDoc(config.accountUrl)
            if (isLoggedIn(doc)) return@runCatchingIo Unit
            val after = performLogin(doc, username, password)
                ?: return@runCatchingIo error("Login-Formular nicht gefunden")
            if (!isLoggedIn(after)) {
                return@runCatchingIo error(loginErrorMessage(after))
            }
            Unit
        }

    override suspend fun loadAccount(username: String, password: String): OpacResult<AccountData> =
        runCatchingIo {
            val accountDoc = authenticatedAccountDoc(username, password)
                ?: return@runCatchingIo error(loginErrorMessage(getDoc(config.accountUrl)))
            val loans = LoanParser.parse(accountDoc)
            AccountData(loans = loans, fees = extractFees(accountDoc))
        }

    override suspend fun renew(username: String, password: String, loan: Loan): OpacResult<Unit> =
        runCatchingIo {
            val accountDoc = authenticatedAccountDoc(username, password)
                ?: return@runCatchingIo error("Nicht eingeloggt")
            val ok = submitRenewal(accountDoc, onlyId = loan.id)
            if (!ok) return@runCatchingIo error("Verlängerung wurde von der Bibliothek nicht bestätigt")
            Unit
        }

    override suspend fun renewAll(username: String, password: String): OpacResult<Unit> =
        runCatchingIo {
            val accountDoc = authenticatedAccountDoc(username, password)
                ?: return@runCatchingIo error("Nicht eingeloggt")
            val ok = submitRenewal(accountDoc, onlyId = null)
            if (!ok) return@runCatchingIo error("Verlängerung wurde von der Bibliothek nicht bestätigt")
            Unit
        }

    override suspend fun captureAccountHtml(username: String, password: String): OpacResult<String> =
        runCatchingIo {
            val accountDoc = authenticatedAccountDoc(username, password)
                ?: getDoc(config.accountUrl)
            accountDoc.outerHtml()
        }

    override suspend fun diagnoseLogin(username: String, password: String): OpacResult<String> =
        runCatchingIo { buildDiagnosticReport(username, password) }

    /**
     * Builds a human-readable diagnostic report of the login page and (if
     * credentials are supplied) the result of an actual login attempt. The
     * password is never written to the report; the user's own account text may
     * appear if the login succeeds, so the UI warns before sharing.
     */
    private fun buildDiagnosticReport(username: String, password: String): String {
        val sb = StringBuilder()
        sb.appendLine("=== Leserkonto Login-Diagnose ===")
        sb.appendLine("Bibliothek: ${config.baseUrl}/${config.branch}")
        sb.appendLine("Account-URL: ${config.accountUrl}")
        sb.appendLine()

        val (status, finalUrl, body) = getRaw(config.accountUrl)
        sb.appendLine("GET Account-Seite -> HTTP $status")
        sb.appendLine("Final-URL: $finalUrl")
        sb.appendLine("Antwort-Größe: ${body.length} Zeichen")

        val doc = Jsoup.parse(body, finalUrl)
        sb.appendLine("Seitentitel: ${doc.title()}")
        sb.appendLine("Bereits eingeloggt erkannt: ${isLoggedIn(doc)}")
        sb.appendLine()

        val forms = doc.select("form")
        sb.appendLine("Gefundene <form>-Elemente: ${forms.size}")
        forms.forEachIndexed { i, f ->
            sb.appendLine(
                "  Form #$i  action='${f.attr("action")}' method='${f.attr("method")}' " +
                    "id='${f.attr("id")}' name='${f.attr("name")}'"
            )
            for (inp in f.select("input, select, textarea")) {
                sb.appendLine(
                    "      ${inp.tagName()} type='${inp.attr("type")}' name='${inp.attr("name")}' " +
                        "id='${inp.attr("id")}' placeholder='${inp.attr("placeholder")}'"
                )
            }
        }
        sb.appendLine()

        val loginForm = findLoginForm(doc)
        sb.appendLine("Login-Formular erkannt: ${loginForm != null}")
        if (loginForm != null) {
            val pwName = loginForm.select("input[type=password]").firstOrNull()?.attr("name")
            sb.appendLine("  Passwortfeld-Name: '$pwName'")
            sb.appendLine("  Erkanntes Benutzerfeld: '${guessUserField(loginForm, pwName ?: "")}'")
        }
        sb.appendLine()

        var resultDoc = doc
        if (loginForm != null && username.isNotBlank() && password.isNotBlank()) {
            sb.appendLine("--- Login-Versuch mit eingegebenen Daten ---")
            val after = performLogin(doc, username, password)
            if (after == null) {
                sb.appendLine("Kein POST möglich (Benutzer-/Passwortfeld nicht bestimmbar).")
            } else {
                resultDoc = after
                val text = after.text().lowercase()
                sb.appendLine("Eingeloggt erkannt: ${isLoggedIn(after)}")
                sb.appendLine("Passwortfeld danach noch vorhanden: ${after.select("input[type=password]").isNotEmpty()}")
                sb.appendLine("Enthält 'abmelden/logout': ${listOf("abmelden", "logout", "ausloggen").any { text.contains(it) }}")
                sb.appendLine("Generierte Fehlermeldung: ${loginErrorMessage(after)}")
            }
        } else {
            sb.appendLine("(Kein Login-Versuch – Felder leer oder bereits eingeloggt.)")
        }
        sb.appendLine()

        // What would the loan parser extract from this page? (and how long it takes)
        val dateRx = Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}""")
        val startNs = System.nanoTime()
        val loans = try {
            LoanParser.parse(resultDoc)
        } catch (e: Throwable) {
            sb.appendLine("PARSER-FEHLER: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
        val ms = (System.nanoTime() - startNs) / 1_000_000
        sb.appendLine("=== Parser-Ergebnis ===")
        sb.appendLine("Erkannte Ausleihen: ${loans.size}  (Dauer ${ms} ms)")
        loans.take(15).forEachIndexed { i, l ->
            sb.appendLine("  [$i] '${l.title}' | fällig=${l.dueDate} | verlängerbar=${l.renewable} | id=${l.id}")
        }
        sb.appendLine("<tr> mit Datum: ${resultDoc.select("tr").count { dateRx.containsMatchIn(it.text()) }}")
        sb.appendLine()

        // Cleaned HTML of the loans area (VIEWSTATE/scripts stripped) so the
        // parsing selectors can be tuned against the real markup.
        sb.appendLine("=== Ausleih-Bereich HTML (bereinigt) ===")
        val loansArea = resultDoc.select("[id*=grdViewLoans], [id*=tpnlLoans], [id*=LoansView]").firstOrNull()
            ?: resultDoc.select("table").maxByOrNull { it.select("tr").size }
            ?: resultDoc.body()
        sb.appendLine(sanitizeHtml(loansArea).take(20000))
        return sb.toString()
    }

    /** Strips bulky/irrelevant nodes (VIEWSTATE, scripts, styles) for readable diagnostics. */
    private fun sanitizeHtml(element: org.jsoup.nodes.Element): String {
        val clone = element.clone()
        clone.select("input[type=hidden], script, style, link, noscript, meta, svg, path, img").remove()
        return clone.outerHtml()
    }

    // ---------------------------------------------------------------- internals

    /** Ensures we are logged in and returns the account page document. */
    private fun authenticatedAccountDoc(username: String, password: String): Document? {
        var doc = getDoc(config.accountUrl)
        if (isLoggedIn(doc)) return doc
        doc = performLogin(doc, username, password) ?: return null
        if (!isLoggedIn(doc)) return null
        // Re-fetch the account page to make sure we are on the loans view.
        val account = getDoc(config.accountUrl)
        return if (isLoggedIn(account)) account else doc
    }

    /** Detects the login form, fills card number + password, submits it. */
    private fun performLogin(doc: Document, username: String, password: String): Document? {
        val form = findLoginForm(doc) ?: return null
        val passwordField = form.select("input[type=password]").first() ?: return null
        val userField = guessUserField(form, passwordField.attr("name")) ?: return null

        val data = collectFormData(form)
        data[userField] = username
        data[passwordField.attr("name")] = password

        // Include the login submit button's name/value if present.
        form.select("input[type=submit], button[type=submit], button")
            .firstOrNull { btnLooksLikeLogin(it.text() + " " + it.attr("value")) }
            ?.let { if (it.attr("name").isNotBlank()) data[it.attr("name")] = it.attr("value") }

        return postForm(form, data)
    }

    /**
     * Finds the actual *login* form. A login form has exactly one password field
     * plus a visible identifier field (card number / user name). This deliberately
     * excludes "PIN/Passwort ändern" forms on the logged-in account page (which
     * carry several password fields and no card-number field), so their presence
     * is not mistaken for "not logged in".
     */
    private fun findLoginForm(doc: Document): FormElement? =
        doc.select("form").filterIsInstance<FormElement>().firstOrNull { isLoginForm(it) }

    private fun isLoginForm(form: FormElement): Boolean {
        // A change-password form has 2-3 password fields; a login has exactly one.
        if (form.select("input[type=password]").size != 1) return false
        // Must also offer a non-password identifier input (the card number).
        return form.select("input").any {
            val type = it.attr("type").lowercase()
            it.attr("name").isNotBlank() &&
                type !in listOf("password", "hidden", "submit", "button", "checkbox", "radio", "image", "reset")
        }
    }

    /**
     * The username field is the visible text/number/email/tel input that comes
     * before the password field (card-number field on BIBLIOTHECA).
     */
    private fun guessUserField(form: FormElement, passwordName: String): String? {
        val candidates = form.select(
            "input[type=text], input[type=number], input[type=email], input[type=tel], input:not([type])"
        ).toList().filter { it.attr("name").isNotBlank() && it.attr("type") != "hidden" }
        // Prefer a field whose name/id hints at a user/card number.
        val hinted = candidates.firstOrNull {
            val key = (it.attr("name") + " " + it.attr("id") + " " + it.attr("placeholder")).lowercase()
            listOf("user", "nummer", "ausweis", "benutzer", "karte", "login", "leser", "kennung")
                .any { h -> key.contains(h) }
        }
        return (hinted ?: candidates.firstOrNull())?.attr("name")?.takeIf { it != passwordName }
    }

    private fun btnLooksLikeLogin(label: String): Boolean {
        val l = label.lowercase()
        return listOf("anmeld", "login", "einlogg", "submit").any { l.contains(it) }
    }

    /**
     * Submits a renewal. If [onlyId] is null, performs "renew all": selects all
     * checkboxes and clicks the renew button. If [onlyId] is set, selects only
     * the matching checkbox.
     */
    private fun submitRenewal(accountDoc: Document, onlyId: String?): Boolean {
        val form = findRenewalForm(accountDoc) ?: return false
        val data = collectFormData(form)

        // Tick the relevant checkboxes (forms usually omit unchecked boxes).
        val checkboxes = form.select("input[type=checkbox]")
        var ticked = 0
        for (cb in checkboxes) {
            val name = cb.attr("name")
            if (name.isBlank()) continue
            val value = cb.attr("value").ifBlank { "on" }
            val matches = onlyId == null || value == onlyId || name == onlyId
            if (matches) { data[name] = value; ticked++ }
        }
        if (onlyId != null && ticked == 0) {
            // No checkbox model — maybe a per-item renew link. Try that instead.
            return submitPerItemRenewLink(accountDoc, onlyId)
        }

        // Include the renew submit button.
        val renewBtn = form.select("input[type=submit], button")
            .firstOrNull { btn ->
                val l = (btn.text() + " " + btn.attr("value") + " " + btn.attr("title")).lowercase()
                listOf("verläng", "verlaeng", "renew").any { l.contains(it) }
            }
        renewBtn?.let { if (it.attr("name").isNotBlank()) data[it.attr("name")] = it.attr("value").ifBlank { "1" } }

        val result = postForm(form, data) ?: return false
        return renewalLooksConfirmed(result)
    }

    private fun submitPerItemRenewLink(doc: Document, id: String): Boolean {
        val link = doc.select("a[href]").firstOrNull {
            val h = it.attr("href").lowercase()
            (h.contains("verläng") || h.contains("verlaeng") || h.contains("renew")) && h.contains(id.lowercase())
        } ?: return false
        val result = getDoc(absolute(doc, link.attr("href")))
        return renewalLooksConfirmed(result)
    }

    private fun findRenewalForm(doc: Document): FormElement? {
        val forms = doc.select("form").filterIsInstance<FormElement>()
        return forms.firstOrNull { form ->
            form.select("input[type=checkbox]").isNotEmpty() ||
                form.select("input[type=submit], button").any {
                    val l = (it.text() + " " + it.attr("value")).lowercase()
                    listOf("verläng", "verlaeng", "renew").any { w -> l.contains(w) }
                }
        }
    }

    private fun renewalLooksConfirmed(doc: Document): Boolean {
        val t = doc.text().lowercase()
        val positive = listOf("erfolgreich", "verlängert", "wurde verläng", "neue leihfrist", "neues rückgabe")
        val negative = listOf("nicht möglich", "fehlgeschlagen", "nicht verläng", "vorgemerkt", "maximal")
        if (negative.any { t.contains(it) } && positive.none { t.contains(it) }) return false
        // If we got back a valid logged-in account page without an error, treat as success.
        return positive.any { t.contains(it) } || isLoggedIn(doc)
    }

    // -------------------------------------------------------------- HTTP helpers

    private fun getDoc(url: String): Document {
        val req = Request.Builder().url(url).apply {
            browserHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return Jsoup.parse(body, resp.request.url.toString())
        }
    }

    /** Like [getDoc] but exposes status code, final URL and raw body for diagnostics. */
    private fun getRaw(url: String): Triple<Int, String, String> {
        val req = Request.Builder().url(url).apply {
            browserHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            return Triple(resp.code, resp.request.url.toString(), resp.body?.string().orEmpty())
        }
    }

    private fun postForm(form: FormElement, data: Map<String, String>): Document? {
        val action = form.attr("action").ifBlank { form.baseUri() }
        val url = absolute(form.ownerDocument() ?: form, action)
        val method = form.attr("method").ifBlank { "post" }.lowercase()

        val builder = if (method == "get") {
            val httpUrl = url.toHttpUrl().newBuilder().apply {
                data.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            Request.Builder().url(httpUrl)
        } else {
            val bodyBuilder = FormBody.Builder()
            data.forEach { (k, v) -> bodyBuilder.add(k, v) }
            Request.Builder().url(url).post(bodyBuilder.build())
        }
        browserHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return Jsoup.parse(body, resp.request.url.toString())
        }
    }

    /** All named inputs except submit buttons (those are added explicitly). */
    private fun collectFormData(form: FormElement): MutableMap<String, String> {
        val data = LinkedHashMap<String, String>()
        for (el in form.select("input, select, textarea")) {
            val name = el.attr("name")
            if (name.isBlank()) continue
            val type = el.attr("type").lowercase()
            when (type) {
                "submit", "button", "image", "reset" -> continue
                "checkbox", "radio" -> if (el.hasAttr("checked")) data[name] = el.attr("value").ifBlank { "on" }
                else -> when (el.tagName()) {
                    "select" -> el.select("option[selected]").firstOrNull()?.let { data[name] = it.attr("value") }
                    "textarea" -> data[name] = el.text()
                    else -> data[name] = el.attr("value")
                }
            }
        }
        return data
    }

    private fun absolute(node: org.jsoup.nodes.Node, href: String): String =
        try {
            if (href.isBlank()) node.baseUri()
            else node.baseUri().toHttpUrl().resolve(href)?.toString() ?: href
        } catch (_: Exception) { href }

    // ------------------------------------------------------------- page analysis

    private fun isLoggedIn(doc: Document): Boolean {
        val t = doc.text().lowercase()
        // Strongest signal: an explicit logout control.
        val hasLogout = listOf("abmelden", "logout", "ausloggen", "abmeldung").any { t.contains(it) }
        if (hasLogout) return true
        // Otherwise: we are logged in if the page shows account content and there
        // is no real login form on it. (A "PIN ändern" password field no longer
        // counts as a login form — see [isLoginForm].)
        val accountMarkers = listOf(
            "entliehene medien", "ausleihen", "entliehen", "rückgabe", "rückgabedatum",
            "verlängerbar", "verlängern", "vormerkung", "vorgemerkt", "gebühren", "leihfrist",
        )
        val looksLikeAccount = accountMarkers.any { t.contains(it) }
        return looksLikeAccount && findLoginForm(doc) == null
    }

    private fun loginErrorMessage(doc: Document): String {
        val t = doc.text()
        val lower = t.lowercase()
        val markers = listOf(
            "passwort" to "ungültig", "kennwort" to "falsch", "benutzer" to "unbekannt",
        )
        for ((a, b) in markers) if (lower.contains(a) && lower.contains(b))
            return "Anmeldung fehlgeschlagen – Ausweisnummer oder Passwort prüfen."
        if (lower.contains("gesperrt")) return "Konto gesperrt – bitte an die Bibliothek wenden."
        return "Anmeldung fehlgeschlagen. Bitte Zugangsdaten prüfen."
    }

    private fun extractFees(doc: Document): String? {
        val el = doc.select("[class*=gebühr], [class*=gebuehr], [class*=fee], [class*=mahn]")
            .firstOrNull { it.text().contains("€") || Regex("""\d+[,.]\d{2}""").containsMatchIn(it.text()) }
        return el?.text()?.trim()
    }

    // -------------------------------------------------------------------- util

    // OkHttp calls block the thread, so we run them on the IO dispatcher.
    private suspend inline fun <T> runCatchingIo(crossinline block: () -> T): OpacResult<T> =
        withContext(Dispatchers.IO) {
            try {
                // Safety net: never let a stuck request/parse hang the UI forever.
                val value = withTimeoutOrNull(45_000L) { block() }
                    ?: return@withContext OpacResult.Error(
                        "Zeitüberschreitung – der Bibliotheksserver antwortet nicht."
                    )
                OpacResult.Success(value)
            } catch (e: OpacException) {
                OpacResult.Error(e.message ?: "Unbekannter Fehler", e)
            } catch (e: Exception) {
                OpacResult.Error("Netzwerk-/Verbindungsfehler: ${e.message}", e)
            }
        }

    private fun error(message: String): Nothing = throw OpacException(message)

    private class OpacException(message: String) : Exception(message)
}
