package app.receiptscanner.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ReceiptNamerTest {

    private val today = LocalDate.of(2026, 11, 5)

    /** Builds OCR lines top-to-bottom; `big` lines get a taller bounding box. */
    private fun lines(vararg rows: String, big: Set<Int> = emptySet()): List<OcrLine> =
        rows.mapIndexed { i, t -> OcrLine(t, top = i * 40, height = if (i in big) 60 else 30) }

    @Test
    fun `typical Norwegian grocery receipt`() {
        val receipt = lines(
            "REMA 1000",
            "Storgata 12, 0155 Oslo",
            "Org.nr 912 345 678 MVA",
            "MELK 1L                24,90",
            "BRØD                   39,90",
            "SUM                    64,80",
            "Herav MVA 15%           8,45",
            "Å BETALE         NOK   64,80",
            "BankAxept              64,80",
            "04.11.2026 18:32  Kasse 3",
            big = setOf(0),
        )
        val info = ReceiptNamer.extract(receipt, today)
        assertEquals("Rema 1000", info.merchant)
        assertEquals(LocalDate.of(2026, 11, 4), info.date)
        assertEquals("64.80", info.total)
        assertEquals("NOK", info.currency)
        assertEquals("2026-11-04 - Rema 1000 - 64.80 NOK", ReceiptNamer.suggestName(info, today))
    }

    @Test
    fun `amount in a separate column on the same row`() {
        val receipt = listOf(
            OcrLine("Kaffebrenneriet", 0, 50),
            OcrLine("Cappuccino", 100, 30),
            OcrLine("52,00", 102, 30),
            OcrLine("TOTAL", 200, 30),
            OcrLine("1 152,00", 205, 30),
        )
        assertEquals("1152.00", ReceiptNamer.findTotal(receipt))
    }

    @Test
    fun `subtotal and VAT lines are not the total`() {
        val receipt = lines("Shop", "Subtotal 80.00", "VAT 20.00", "Total 100.00")
        assertEquals("100.00", ReceiptNamer.findTotal(receipt))
    }

    @Test
    fun `falls back to largest amount`() {
        val receipt = lines("Shop", "Item 12,00", "Item 30,50")
        assertEquals("30.50", ReceiptNamer.findTotal(receipt))
    }

    @Test
    fun `date formats`() {
        assertEquals(LocalDate.of(2026, 3, 7), ReceiptNamer.findDate(lines("2026-03-07 12:00"), today))
        assertEquals(LocalDate.of(2026, 3, 7), ReceiptNamer.findDate(lines("07/03/26"), today))
        assertEquals(LocalDate.of(2026, 10, 31), ReceiptNamer.findDate(lines("10/31/2026"), today))
        assertEquals(LocalDate.of(2026, 10, 2), ReceiptNamer.findDate(lines("2. okt 2026"), today))
        assertEquals(LocalDate.of(2025, 12, 24), ReceiptNamer.findDate(lines("24 Dec 2025"), today))
    }

    @Test
    fun `future dates and garbage are ignored`() {
        assertNull(ReceiptNamer.findDate(lines("01.01.2030", "Kort: ****1234"), today))
    }

    @Test
    fun `merchant skips headers and contact info`() {
        val receipt = lines("KVITTERING", "Tlf 22 22 22 22", "Kiwi Majorstuen", "www.kiwi.no")
        assertEquals("Kiwi Majorstuen", ReceiptNamer.findMerchant(receipt))
    }

    @Test
    fun `prettify keeps short acronyms`() {
        assertEquals("XXL Sport", ReceiptNamer.prettify("XXL SPORT"))
        assertEquals("7-Eleven", ReceiptNamer.prettify("7-ELEVEN"))
    }

    @Test
    fun `empty receipt still gets a name`() {
        val info = ReceiptNamer.extract(emptyList(), today)
        assertEquals("2026-11-05 - Receipt", ReceiptNamer.suggestName(info, today))
    }

    @Test
    fun `sanitize removes path characters`() {
        assertEquals("A B C", ReceiptNamer.sanitize("A/B:C"))
    }

    @Test
    fun `trip folder is destination, month and year`() {
        assertEquals("Oslo November 2026", ReceiptNamer.tripFolderName("Oslo", today))
        assertEquals("New York March 2027", ReceiptNamer.tripFolderName("  New  York ", LocalDate.of(2027, 3, 1)))
    }

    @Test
    fun `trip folder ignores the device language`() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("nb-NO"))
        try {
            assertEquals("Oslo November 2026", ReceiptNamer.tripFolderName("Oslo", today))
        } finally {
            Locale.setDefault(original)
        }
    }
}
