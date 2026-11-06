package app.receiptscanner.scan

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of OCR output, with its vertical position on the image (pixels). */
data class OcrLine(val text: String, val top: Int, val height: Int) {
    val centerY: Int get() = top + height / 2
}

data class ReceiptInfo(
    val merchant: String?,
    val date: LocalDate?,
    val total: String?,
    val currency: String?,
)

/**
 * Pure-Kotlin heuristics that turn OCR lines into a [ReceiptInfo] and a file name.
 * Tuned for Norwegian/European receipts, with English fallbacks.
 */
object ReceiptNamer {

    fun suggestName(info: ReceiptInfo, fallbackDate: LocalDate): String {
        val parts = mutableListOf((info.date ?: fallbackDate).toString())
        parts += info.merchant ?: "Receipt"
        if (info.total != null) parts += listOfNotNull(info.total, info.currency).joinToString(" ")
        return sanitize(parts.joinToString(" - "))
    }

    fun extract(lines: List<OcrLine>, today: LocalDate = LocalDate.now()): ReceiptInfo {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.top }
        return ReceiptInfo(
            merchant = findMerchant(sorted),
            date = findDate(sorted, today),
            total = findTotal(sorted),
            currency = findCurrency(sorted),
        )
    }

    // ---------------------------------------------------------------- date

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mai" to 5, "may" to 5,
        "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "okt" to 10, "oct" to 10,
        "nov" to 11, "des" to 12, "dec" to 12,
    )
    private val ISO_DATE = Regex("""(?<!\d)(\d{4})[-./](\d{1,2})[-./](\d{1,2})(?!\d)""")
    private val DMY_DATE = Regex("""(?<!\d)(\d{1,2})[-./](\d{1,2})[-./](\d{4}|\d{2})(?!\d)""")
    private val TEXT_DATE = Regex("""(?<!\d)(\d{1,2})\.?\s*([a-zA-Z]{3})[a-zA-Z]*\.?\s*(\d{4})(?!\d)""")

    internal fun findDate(lines: List<OcrLine>, today: LocalDate): LocalDate? {
        for (line in lines) {
            val t = line.text
            ISO_DATE.findAll(t).forEach { m ->
                val (y, mo, d) = m.destructured
                valid(y.toInt(), mo.toInt(), d.toInt(), today)?.let { return it }
            }
            DMY_DATE.findAll(t).forEach { m ->
                val (a, b, y) = m.destructured
                val year = y.toInt().let { if (it < 100) 2000 + it else it }
                // European day-first; swap if that is impossible (US-style receipt).
                (valid(year, b.toInt(), a.toInt(), today) ?: valid(year, a.toInt(), b.toInt(), today))
                    ?.let { return it }
            }
            TEXT_DATE.findAll(t).forEach { m ->
                val (d, mon, y) = m.destructured
                val month = MONTHS[mon.lowercase()] ?: return@forEach
                valid(y.toInt(), month, d.toInt(), today)?.let { return it }
            }
        }
        return null
    }

    private fun valid(y: Int, m: Int, d: Int, today: LocalDate): LocalDate? {
        if (y < 2000 || m !in 1..12 || d !in 1..31) return null
        val date = runCatching { LocalDate.of(y, m, d) }.getOrNull() ?: return null
        return date.takeIf { !it.isAfter(today.plusDays(1)) }
    }

    // ---------------------------------------------------------------- total

    /** Amount with exactly two decimals, optional thousands separators: 1 234,50 / 1.234,50 / 249.90 */
    private val AMOUNT = Regex("""(?<![\d.,])(\d{1,3}(?:[ . ]\d{3})+|\d+)[.,](\d{2})(?![\d])""")

    private val STRONG_TOTAL = listOf(
        "å betale", "a betale", "å bet", "totalt", "total", "grand total", "amount due",
        "balance due", "to pay", "att betala", "at betale", "summe",
    )
    private val WEAK_TOTAL = listOf("sum", "beløp", "belop", "bel0p", "betalt", "kort", "bankaxept")
    private val NOT_TOTAL = listOf(
        "subtotal", "sub total", "delsum", "mva", "moms", "vat", "tax", "netto", "eks.", "ekskl",
        "grunnlag", "rabatt", "discount", "pant", "tips", "veksel", "change",
    )

    internal fun findTotal(lines: List<OcrLine>): String? {
        for (keywords in listOf(STRONG_TOTAL, WEAK_TOTAL)) {
            // Last matching line wins: totals sit at the bottom, item lines above.
            val candidates = lines.filter { line ->
                val l = line.text.lowercase()
                keywords.any { l.contains(it) } && NOT_TOTAL.none { l.contains(it) }
            }
            for (line in candidates.asReversed()) {
                amountNear(line, lines)?.let { return it }
            }
        }
        // Fallback: largest amount anywhere on the receipt.
        return lines.flatMap { line -> AMOUNT.findAll(line.text).map { normalize(it) }.toList() }
            .maxByOrNull { it.toDouble() }
    }

    /** Amount on the keyword line itself, or on a line in the same row (ML Kit often splits columns). */
    private fun amountNear(line: OcrLine, all: List<OcrLine>): String? {
        AMOUNT.findAll(line.text).lastOrNull()?.let { return normalize(it) }
        val tolerance = maxOf(line.height * 3 / 4, 8)
        return all.asSequence()
            .filter { it !== line && kotlin.math.abs(it.centerY - line.centerY) <= tolerance }
            .mapNotNull { AMOUNT.findAll(it.text).lastOrNull() }
            .map { normalize(it) }
            .firstOrNull()
    }

    private fun normalize(m: MatchResult): String {
        val whole = m.groupValues[1].replace(Regex("""[ . ]"""), "")
        return "${whole.trimStart('0').ifEmpty { "0" }}.${m.groupValues[2]}"
    }

    // ---------------------------------------------------------------- currency

    private val CURRENCIES = listOf(
        "NOK" to listOf("nok", "kr"), "SEK" to listOf("sek"), "DKK" to listOf("dkk"),
        "EUR" to listOf("eur", "€"), "GBP" to listOf("gbp", "£"), "USD" to listOf("usd", "$"),
    )

    internal fun findCurrency(lines: List<OcrLine>): String? {
        val text = lines.joinToString(" ") { it.text.lowercase() }
        // Explicit ISO codes first, then symbols/"kr".
        CURRENCIES.firstOrNull { (code, _) -> Regex("""\b${code.lowercase()}\b""").containsMatchIn(text) }
            ?.let { return it.first }
        if (Regex("""\bkr\b""").containsMatchIn(text)) return "NOK"
        return CURRENCIES.firstOrNull { (_, tokens) -> tokens.any { it.length == 1 && text.contains(it) } }?.first
    }

    // ---------------------------------------------------------------- merchant

    private val NOT_MERCHANT = listOf(
        "kvittering", "receipt", "salgskvittering", "velkommen", "welcome", "kopi", "copy",
        "org", "foretaksregisteret", "mva", "vat", "tlf", "tel", "phone", "www", "http", "@",
        ".no", ".com", "kasse", "dato", "date", "bong", "ekspeditør", "betjent", "cashier",
    )

    internal fun findMerchant(lines: List<OcrLine>): String? {
        val top = lines.take(8)
        return top.withIndex()
            .filter { (_, line) -> looksLikeName(line.text) }
            .maxByOrNull { (index, line) -> line.height * (1.0 - 0.08 * index) }
            ?.value?.text?.let(::prettify)
    }

    private fun looksLikeName(text: String): Boolean {
        val t = text.trim()
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        if (letters < 2 || digits > letters) return false
        val lower = t.lowercase()
        if (NOT_MERCHANT.any { lower.contains(it) }) return false
        if (DMY_DATE.containsMatchIn(t) || ISO_DATE.containsMatchIn(t)) return false
        return true
    }

    /** "REMA 1000" -> "Rema 1000", but keep short acronyms like "XXL" or "IKEA"-length tokens ≤ 3. */
    internal fun prettify(text: String): String =
        text.trim().split(Regex("""\s+""")).joinToString(" ") { word ->
            if (word.count { it.isLetter() } > 3 && word.all { !it.isLetter() || it.isUpperCase() }) {
                // Capitalize the first letter of each letter-run: "7-ELEVEN" -> "7-Eleven".
                word.lowercase().mapIndexed { i, c ->
                    if (c.isLetter() && (i == 0 || !word[i - 1].isLetter())) c.titlecaseChar() else c
                }.joinToString("")
            } else word
        }

    // ---------------------------------------------------------------- filename

    fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .trim()

    // ---------------------------------------------------------------- trip folder

    // Fixed locale: folder names shouldn't change with the phone's language.
    private val TRIP_MONTH = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)

    /** "Oslo" + a date in November 2026 -> "Oslo November 2026". */
    fun tripFolderName(destination: String, start: LocalDate): String =
        "${sanitize(destination)} ${start.format(TRIP_MONTH)}"
}
