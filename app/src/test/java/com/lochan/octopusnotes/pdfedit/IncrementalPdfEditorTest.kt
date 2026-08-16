package com.lochan.octopusnotes.pdfedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalPdfEditorTest {

    private fun reopen(bytes: ByteArray): IncrementalPdfEditor {
        val tmp = java.io.File.createTempFile("pdfedit_test", ".pdf")
        tmp.writeBytes(bytes)
        return try {
            IncrementalPdfEditor.open(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun dictOf(bytes: ByteArray, num: Int): PdfObject.Dict {
        val xref = PdfXref.parse(bytes)
        val e: PdfXrefEntry = xref.entries[num] ?: error("object $num missing")
        val lex = PdfLexer(bytes, e.offset.toInt())
        lex.next(); lex.next(); lex.next()
        return lex.parseValue() as PdfObject.Dict
    }

    private fun saveBytes(editor: IncrementalPdfEditor): ByteArray {
        val tmp = java.io.File.createTempFile("pdfedit_save", ".pdf")
        return try {
            editor.save(tmp)
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun opensAndCountsPages() {
        val bytes = TestPdfFactory.threePagePdf()
        assertEquals(3, reopen(bytes).pageCount)
    }

    @Test
    fun rejectsEncrypted() {
        val bytes = TestPdfFactory.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                "<< /Type /Pages /Kids [] /Count 0 >>".toByteArray()
            ),
            "/Size 3 /Root 1 0 R /Encrypt 9 0 R"
        )
        try {
            reopen(bytes)
            error("expected Unsupported")
        } catch (e: PdfEditException.Unsupported) {
            assertTrue(e.message!!.contains("ncrypt"))
        }
    }

    @Test
    fun rejectsCorruptXrefStream() {

        val bytes = TestPdfFactory.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                "<< /Type /Pages /Kids [] /Count 0 >>".toByteArray()
            ),
            "/Size 3 /Root 1 0 R"
        )
        val text = String(bytes, Charsets.ISO_8859_1)

        val withXrefStream = text.replaceFirst(
            "xref\n",
            "1 0 obj\n<< /Type /XRef /W [1 4 1] /Size 3 /Root 1 0 R >>\nstream\nx\nendstream\nendobj\n"
        )
        try {
            reopen(withXrefStream.toByteArray(Charsets.ISO_8859_1))
            error("expected Corrupt")
        } catch (e: PdfEditException.Corrupt) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun rotatesPageInPlace() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        editor.rotatePage(1, 90)
        val saved = saveBytes(editor)

        assertEquals("90", dictOf(saved, 4).map["Rotate"]?.asNumber?.raw)
        assertEquals(3, reopen(saved).pageCount)
    }

    @Test
    fun rotateTwiceCumulates() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        editor.rotatePage(1, 90)
        val saved = saveBytes(editor)

        val editor2 = reopen(saved)
        editor2.rotatePage(1, -180)
        val saved2 = saveBytes(editor2)
        assertEquals("270", dictOf(saved2, 4).map["Rotate"]?.asNumber?.raw)
    }

    @Test
    fun deletesPagesAndUpdatesCount() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        editor.deletePages(listOf(2, 0))
        val saved = saveBytes(editor)

        val r = reopen(saved)
        assertEquals(1, r.pageCount)
        val pages = dictOf(saved, 2)
        assertEquals("1", pages.map["Count"]?.asNumber?.raw)
        val kids = pages.map["Kids"] as PdfObject.ArrayRef
        assertEquals(1, kids.items.size)

        assertEquals(PdfObject.Ref(4, 0), kids.items[0])
    }

    @Test
    fun movesPage() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        editor.movePage(0, 2)
        val saved = saveBytes(editor)

        val kids = (dictOf(saved, 2).map["Kids"] as PdfObject.ArrayRef).items
        assertEquals(listOf(PdfObject.Ref(4, 0), PdfObject.Ref(5, 0), PdfObject.Ref(3, 0)), kids)
        assertEquals(3, reopen(saved).pageCount)
    }

    @Test
    fun movePageFromEndToStart() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        editor.movePage(2, 0)
        val saved = saveBytes(editor)
        val kids = (dictOf(saved, 2).map["Kids"] as PdfObject.ArrayRef).items
        assertEquals(listOf(PdfObject.Ref(5, 0), PdfObject.Ref(3, 0), PdfObject.Ref(4, 0)), kids)
    }

    @Test
    fun duplicatesPage() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        editor.duplicatePage(1)
        val saved = saveBytes(editor)

        assertEquals(4, reopen(saved).pageCount)
        val kids = (dictOf(saved, 2).map["Kids"] as PdfObject.ArrayRef).items
        assertEquals(4, kids.size)
        assertEquals(PdfObject.Ref(4, 0), kids[1])
        assertEquals(PdfObject.Ref(9, 0), kids[2])
    }

    @Test
    fun insertsTemplatePage() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        editor.insertTemplatePage(1, 612f, 792f, jpeg, 4, 4)
        val saved = saveBytes(editor)

        assertEquals(4, reopen(saved).pageCount)
        val kids = (dictOf(saved, 2).map["Kids"] as PdfObject.ArrayRef).items
        assertEquals(4, kids.size)

        assertEquals(PdfObject.Ref(12, 0), kids[1])
        val newPage = dictOf(saved, 12)
        assertTrue(newPage.map["Contents"] is PdfObject.Ref)
        assertTrue(newPage.map["Resources"] is PdfObject.Ref)
    }

    @Test
    fun appliesTemplateToPages() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        editor.applyTemplate(listOf(0, 2), jpeg, 4, 4, prepend = false)
        val saved = saveBytes(editor)

        val contents = dictOf(saved, 3).map["Contents"] as PdfObject.ArrayRef
        assertEquals(2, contents.items.size)
        assertEquals(PdfObject.Ref(6, 0), contents.items[0])
    }

    @Test
    fun incrementalSaveIsAppendOnlyWithPrev() {
        val original = TestPdfFactory.threePagePdf()
        val origStart = PdfXref.parse(original).startXref
        val editor = reopen(original)
        editor.rotatePage(0, 90)
        val saved = saveBytes(editor)

        assertTrue(saved.size > original.size)
        val xref = PdfXref.parse(saved)
        val prev = xref.trailer.map["Prev"]?.asNumber?.intValue
        assertEquals(origStart, prev ?: error("no /Prev"))
        assertTrue((prev ?: 0L) < xref.startXref)
    }

    @Test
    fun doubleIncrementalStillReads() {
        val original = TestPdfFactory.threePagePdf()
        var editor = reopen(original)
        editor.rotatePage(0, 90)
        var saved = saveBytes(editor)

        editor = reopen(saved)
        editor.rotatePage(1, -90)
        saved = saveBytes(editor)

        val r = reopen(saved)
        assertEquals(3, r.pageCount)
        assertEquals("90", dictOf(saved, 3).map["Rotate"]?.asNumber?.raw)
        assertEquals("270", dictOf(saved, 4).map["Rotate"]?.asNumber?.raw)
    }

    @Test
    fun serializerRoundTripsNamesAndStrings() {
        val obj = PdfObject.Dict().also { d ->
            d.map["Type"] = PdfObject.Name("Page")
            d.map["Name#With#Hash"] = PdfObject.Name("weird/name")
            d.map["Bin"] = PdfObject.Str(byteArrayOf(0x00, 0x01, 0xFF.toByte()))
            d.map["Arr"] = PdfObject.ArrayRef(mutableListOf(PdfObject.Number("42"), PdfObject.Bool(true)))
        }
        val bytes = obj.toByteArray()
        val lex = PdfLexer(bytes, 0)
        lex.next()
        val reparsed = lex.parseDictBody()
        assertEquals("Page", reparsed.map["Type"]?.asName?.name)
        assertEquals("weird/name", reparsed.map["Name#With#Hash"]?.asName?.name)
        val bin = (reparsed.map["Bin"] as PdfObject.Str).bytes
        assertEquals(3, bin.size)
        assertEquals(0xFF.toByte(), bin[2])
    }

    @Test
    fun blankPageHasNoFontResources() {
        val original = TestPdfFactory.threePagePdf()
        val editor = reopen(original)
        assertEquals(false, editor.pageHasFontResources(0))
    }
}
