package com.lochan.octopusnotes.pdfedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFeatureTest {

    @Test
    fun `reads nested outline with ref and named destinations`() {
        val items = PdfOutlineReader.read(TestPdfFactory.outlinePdf())
        assertEquals(2, items.size)

        val one = items[0]
        assertEquals("Chapter One", one.title)
        assertEquals(0, one.pageIndex)
        assertEquals(0, one.depth)
        assertEquals(1, one.children.size)
        assertEquals("Section 1.1", one.children[0].title)
        assertEquals(1, one.children[0].pageIndex)
        assertEquals(1, one.children[0].depth)

        val two = items[1]
        assertEquals("Chapter Two", two.title)
        assertEquals(0, two.pageIndex)
    }

    @Test
    fun `outline of a pdf without one is empty`() {
        assertTrue(PdfOutlineReader.read(TestPdfFactory.threePagePdf()).isEmpty())
    }

    @Test
    fun `cyclic named destinations cap recursion instead of overflowing`() {

        assertTrue(PdfOutlineReader.read(TestPdfFactory.cyclicDestsPdf()).isEmpty())
    }

    @Test
    fun `outline survives object-stream packed pages`() {
        val items = PdfOutlineReader.read(TestPdfFactory.objStmPdf())

        assertTrue(items.isEmpty())
    }

    private val winAnsiFont: String =
        "/Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
            "/FirstChar 32 /Widths [" + (0 until 96).joinToString(" ") { "500" } + "]"

    @Test
    fun `extracts a simple Tj line with WinAnsi font`() {
        val pdf = TestPdfFactory.textPagePdf("BT /F1 24 Tf 72 720 Td (Hello World) Tj ET", winAnsiFont)
        val doc = PdfDoc.open(pdf)
        val lines = PdfTextExtractor().extractPage(doc, 0)
        assertEquals(1, lines.size)
        assertEquals("Hello World", lines[0].text)
        assertEquals(11, lines[0].glyphStarts.size)
        assertEquals(11 * 4, lines[0].rects.size)

        val r0 = lines[0].rects
        assertTrue(r0[0] < r0[2])
        assertTrue(r0[4] > r0[0])
        assertTrue(r0[1] in 0f..1f && r0[3] in 0f..1f)
    }

    @Test
    fun `extracts two lines separated by Td`() {
        val pdf = TestPdfFactory.textPagePdf(
            "BT /F1 24 Tf 72 720 Td (First) Tj 0 -30 Td (Second) Tj ET",
            winAnsiFont
        )
        val doc = PdfDoc.open(pdf)
        val lines = PdfTextExtractor().extractPage(doc, 0)
        assertEquals(2, lines.size)
        assertEquals("First", lines[0].text)
        assertEquals("Second", lines[1].text)

        assertTrue(lines[1].rects[1] > lines[0].rects[1])
    }

    @Test
    fun `extracts TJ with kerning adjustments`() {
        val pdf = TestPdfFactory.textPagePdf(
            "BT /F1 24 Tf 72 720 Td [(Hel) -120 (lo)] TJ ET",
            winAnsiFont
        )
        val doc = PdfDoc.open(pdf)
        val lines = PdfTextExtractor().extractPage(doc, 0)
        assertEquals(1, lines.size)
        assertEquals("Hello", lines[0].text)
    }

    @Test
    fun `extracts Type0 text through ToUnicode bfchar`() {
        val pdf = TestPdfFactory.textPageType0Pdf(
            "BT /F1 24 Tf 72 720 Td (\u0000A\u0000B) Tj ET",
            "/Type /Font /Subtype /CIDFontType2 /BaseFont /Test /CIDSystemInfo << /Registry (A) /Ordering (B) /Supplement 0 >> " +
                "/DW 500 /W [65 [500 500] 67 68 500]",
            "beginbfchar\n<0041> <0041>\n<0042> <0042>\nendbfchar\n"
        )
        val doc = PdfDoc.open(pdf)
        val lines = PdfTextExtractor().extractPage(doc, 0)
        assertEquals(1, lines.size)
        assertEquals("AB", lines[0].text)
    }

    @Test
    fun `extracts Type0 text through ToUnicode bfrange`() {
        val pdf = TestPdfFactory.textPageType0Pdf(
            "BT /F1 24 Tf 72 720 Td (\u0000C\u0000D\u0000E) Tj ET",
            "/Type /Font /Subtype /CIDFontType2 /BaseFont /Test /CIDSystemInfo << /Registry (A) /Ordering (B) /Supplement 0 >> " +
                "/DW 500",
            "beginbfrange\n<0043> <0045> <0043>\nendbfrange\n"
        )
        val doc = PdfDoc.open(pdf)
        val lines = PdfTextExtractor().extractPage(doc, 0)
        assertEquals(1, lines.size)
        assertEquals("CDE", lines[0].text)
    }

    @Test
    fun `skips inline images without derailing the lexer`() {
        val pdf = TestPdfFactory.textPagePdf(
            "q 100 0 0 100 0 0 cm BI /W 1 /H 1 /CS /RGB /BPC 8 ID \u0000\u0000\u0000 EI Q " +
                "BT /F1 24 Tf 72 720 Td (After) Tj ET",
            winAnsiFont
        )
        val doc = PdfDoc.open(pdf)
        val lines = PdfTextExtractor().extractPage(doc, 0)
        assertEquals(1, lines.size)
        assertEquals("After", lines[0].text)
    }

    @Test
    fun `returns empty for a page with no text`() {
        val pdf = TestPdfFactory.threePagePdf()
        val doc = PdfDoc.open(pdf)
        assertTrue(PdfTextExtractor().extractPage(doc, 0).isEmpty())
    }

    @Test
    fun `reads uri and internal links`() {
        val links = PdfLinkReader.read(TestPdfFactory.linkPdf())
        assertEquals(2, links.size)

        val uri = links[0]
        assertEquals(0, uri.pageIndex)
        assertEquals("https://example.com/doc", uri.uri)
        assertEquals(-1, uri.destPage)
        assertTrue(uri.rect != null)

        val r = uri.rect!!
        assertTrue(r[0] < r[2])
        assertTrue(r[1] < r[3])
        assertTrue(r[0] > 0f && r[2] < 1f)

        val go = links[1]
        assertEquals(0, go.pageIndex)
        assertEquals(null, go.uri)
        assertEquals(1, go.destPage)
    }

    @Test
    fun `pdf without links yields empty`() {
        assertTrue(PdfLinkReader.read(TestPdfFactory.threePagePdf()).isEmpty())
    }

    @Test
    fun `opens a pdf with an xref stream`() {
        val doc = PdfDoc.open(TestPdfFactory.xrefStreamPdf())
        assertEquals(1, doc.pageCount)
        assertEquals(612.0f, doc.pageSize(0).first, 1f)
        assertEquals(792.0f, doc.pageSize(0).second, 1f)
    }

    @Test
    fun `flate stream needing a preset dictionary fails instead of returning partial data`() {
        val d = java.util.zip.Deflater()
        d.setDictionary(byteArrayOf(1, 2, 3, 4, 5))
        d.setInput("hello preset dictionary content".toByteArray(Charsets.US_ASCII))
        d.finish()
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(64)
        while (!d.finished()) buf.write(tmp, 0, d.deflate(tmp))
        d.end()

        assertNull(PdfFilters.inflate(buf.toByteArray()))
    }

    @Test
    fun `truncated flate stream fails instead of returning partial data`() {

        val payload = String(CharArray(20000) { 'a' }).toByteArray(Charsets.US_ASCII)
        val d = java.util.zip.Deflater()
        d.setInput(payload)
        d.finish()
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(256)
        while (!d.finished()) buf.write(tmp, 0, d.deflate(tmp))
        d.end()
        val full = buf.toByteArray()
        val truncated = full.copyOfRange(0, full.size * 3 / 4)
        assertNull(PdfFilters.inflate(truncated))

        assertEquals(payload.size, PdfFilters.inflate(full)!!.size)
    }

    @Test
    fun `parses xref stream with 8-byte offsets`() {

        val doc = PdfDoc.open(TestPdfFactory.xrefStreamPdf(f2Width = 8))
        assertEquals(1, doc.pageCount)
        assertEquals(612.0f, doc.pageSize(0).first, 1f)
        assertEquals(792.0f, doc.pageSize(0).second, 1f)
    }

    @Test
    fun `xref offset wider than Int fails cleanly instead of wrapping`() {

        val big = (1L shl 32) + 1234L
        try {
            PdfDoc.open(TestPdfFactory.xrefStreamPdf(f2Width = 8, hugeOffsetForObj = big))
            org.junit.Assert.fail("expected PdfEditException.Corrupt for out-of-range offset")
        } catch (e: PdfEditException.Corrupt) {

        }
    }

    @Test
    fun `edits a pdf with an xref stream and reopens the result`() {
        val data = TestPdfFactory.xrefStreamPdf()
        val editor = IncrementalPdfEditor.open(java.io.File.createTempFile("xst", ".pdf").also { it.writeBytes(data) })
        editor.rotatePage(0, 90)
        val tmp = java.io.File.createTempFile("xst_out", ".pdf")
        editor.save(tmp)

        val reopened = PdfDoc.open(tmp.readBytes())
        assertEquals(1, reopened.pageCount)
        val rotated = reopened.pageDict(0)
        assertEquals("90", rotated.map["Rotate"]?.asNumber?.raw)
    }

    @Test
    fun `loads objects packed in an object stream`() {
        val doc = PdfDoc.open(TestPdfFactory.objStmPdf())
        assertEquals(1, doc.pageCount)
        val page = doc.pageDict(0)
        assertEquals("Page", page.map["Type"]?.asName?.name)
    }

    @Test
    fun `edits a page packed in an object stream`() {
        val data = TestPdfFactory.objStmPdf()
        val editor = IncrementalPdfEditor.open(java.io.File.createTempFile("objstm", ".pdf").also { it.writeBytes(data) })
        editor.rotatePage(0, 180)
        val tmp = java.io.File.createTempFile("objstm_out", ".pdf")
        editor.save(tmp)

        val reopened = PdfDoc.open(tmp.readBytes())
        assertEquals(1, reopened.pageCount)
        assertEquals("180", reopened.pageDict(0).map["Rotate"]?.asNumber?.raw)
    }
}
