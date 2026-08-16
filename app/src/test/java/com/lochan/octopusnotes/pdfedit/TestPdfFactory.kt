package com.lochan.octopusnotes.pdfedit

import java.io.ByteArrayOutputStream

object TestPdfFactory {

    fun threePagePdf(): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int {
            objs.add(s.toByteArray(Charsets.US_ASCII))
            return objs.size
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Rotate 0 /Contents 7 0 R >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 8 0 R >>")
        add("<< /Length 5 >>\nstream\nhello\nendstream")
        add("<< /Length 5 >>\nstream\nworld\nendstream")
        add("<< /Length 5 >>\nstream\nthere\nendstream")
        return build(objs, "/Size 9 /Root 1 0 R")
    }

    fun build(objects: List<ByteArray>, trailerExtra: String = "/Size ${objects.size + 1} /Root 1 0 R"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val offsets = LongArray(objects.size)
        for ((i, body) in objects.withIndex()) {
            offsets[i] = out.size().toLong()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }
        val xrefPos = out.size().toLong()
        out.write("xref\n".toByteArray(Charsets.US_ASCII))
        out.write("0 ${objects.size + 1}\n".toByteArray(Charsets.US_ASCII))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
        for (off in offsets) {
            out.write(String.format("%010d 00000 n \n", off).toByteArray(Charsets.US_ASCII))
        }
        out.write("trailer\n<< $trailerExtra >>\n".toByteArray(Charsets.US_ASCII))
        out.write("startxref\n$xrefPos\n%%EOF\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    fun textPagePdf(content: String, fontDictBody: String, toUnicodeBody: String? = null): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int { objs.add(s.toByteArray(Charsets.US_ASCII)); return objs.size }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>")
        val contentBytes = content.toByteArray(Charsets.US_ASCII)
        add("<< /Length ${contentBytes.size} >>\nstream\n${content}\nendstream")
        val fontBody = if (toUnicodeBody != null) "$fontDictBody /ToUnicode 6 0 R" else fontDictBody
        add("<< $fontBody >>")
        if (toUnicodeBody != null) {
            val tu = toUnicodeBody.toByteArray(Charsets.US_ASCII)
            add("<< /Length ${tu.size} >>\nstream\n${toUnicodeBody}\nendstream")
        }
        return build(objs, "/Size ${objs.size + 1} /Root 1 0 R")
    }

    fun textPageType0Pdf(content: String, descendantBody: String, toUnicodeBody: String): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int { objs.add(s.toByteArray(Charsets.US_ASCII)); return objs.size }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>")
        val contentBytes = content.toByteArray(Charsets.US_ASCII)
        add("<< /Length ${contentBytes.size} >>\nstream\n${content}\nendstream")
        add("<< /Type /Font /Subtype /Type0 /BaseFont /Test /Encoding /Identity-H " +
            "/DescendantFonts [6 0 R] /ToUnicode 7 0 R >>")
        add("<< $descendantBody >>")
        val tu = toUnicodeBody.toByteArray(Charsets.US_ASCII)
        add("<< /Length ${tu.size} >>\nstream\n${toUnicodeBody}\nendstream")
        return build(objs, "/Size ${objs.size + 1} /Root 1 0 R")
    }

    fun outlinePdf(): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int { objs.add(s.toByteArray(Charsets.US_ASCII)); return objs.size }
        add("<< /Type /Catalog /Pages 6 0 R /Outlines 2 0 R /Dests 7 0 R >>")
        add("<< /Type /Outlines /First 8 0 R /Last 9 0 R /Count 2 >>")
        add("<< /Type /Page /Parent 6 0 R /MediaBox [0 0 612 792] >>")
        add("<< /Type /Page /Parent 6 0 R /MediaBox [0 0 612 792] >>")
        add("<< /Type /Page /Parent 6 0 R /MediaBox [0 0 612 792] >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>")
        add("<< /NamedOne [3 0 R /XYZ 0 0 0] /NamedTwo /NamedOne >>")
        add("<< /Title (Chapter One) /Dest [3 0 R /XYZ 0 792 0] /Next 9 0 R /First 10 0 R >>")
        add("<< /Title (Chapter Two) /Dest /NamedOne >>")
        add("<< /Title (Section 1.1) /Dest [4 0 R /Fit] >>")
        return build(objs, "/Size ${objs.size + 1} /Root 1 0 R")
    }

    fun linkPdf(): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int { objs.add(s.toByteArray(Charsets.US_ASCII)); return objs.size }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 7 0 R] /Count 2 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Contents 4 0 R /Annots [5 0 R 6 0 R] >>")
        add("<< /Length 5 >>\nstream\nhello\nendstream")
        add("<< /Type /Annot /Subtype /Link /Rect [100 700 300 720] " +
            "/A << /S /URI /URI (https://example.com/doc) >> >>")
        add("<< /Type /Annot /Subtype /Link /Rect [100 650 300 670] " +
            "/A << /S /GoTo /D [7 0 R /Fit] >> >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
        return build(objs, "/Size ${objs.size + 1} /Root 1 0 R")
    }

    fun cyclicDestsPdf(): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int { objs.add(s.toByteArray(Charsets.US_ASCII)); return objs.size }
        add("<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R /Dests 6 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
        add("<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>")
        add("<< /Title (Cyclic) /Dest /a >>")
        add("<< /a /b /b /a >>")
        return build(objs, "/Size ${objs.size + 1} /Root 1 0 R")
    }

    fun xrefStreamPdf(f2Width: Int = 4, hugeOffsetForObj: Long? = null): ByteArray {
        val bodies = mutableListOf<ByteArray>()
        fun add(s: String): Int { bodies.add(s.toByteArray(Charsets.US_ASCII)); return bodies.size }
        add("<< /Type /Catalog /Pages 3 0 R >>")
        add("<< /Type /Pages /Kids [4 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] /Contents 5 0 R >>")
        add("<< /Length 5 >>\nstream\nhello\nendstream")

        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray(Charsets.US_ASCII))

        val objOffsets = IntArray(bodies.size)
        for ((i, body) in bodies.withIndex()) {
            objOffsets[i] = out.size()
            out.write("${i + 2} 0 obj\n".toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }

        val xrefStart = out.size()
        val rec = java.io.ByteArrayOutputStream()
        fun writeRec(t: Int, f2: Long, f3: Int) {
            rec.write(t)
            for (shift in (8 * f2Width - 8) downTo 0 step 8) rec.write(((f2 ushr shift) and 0xFF).toInt())
            rec.write((f3 ushr 8) and 0xFF); rec.write(f3 and 0xFF)
        }
        writeRec(0, 0L, 65535)
        writeRec(1, xrefStart.toLong(), 0)
        writeRec(1, hugeOffsetForObj ?: objOffsets[0].toLong(), 0)
        writeRec(1, objOffsets[1].toLong(), 0)
        writeRec(1, objOffsets[2].toLong(), 0)
        writeRec(1, objOffsets[3].toLong(), 0)
        val flat = java.util.zip.Deflater().run {
            setInput(rec.toByteArray())
            finish()
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(256)
            while (!finished()) buf.write(tmp, 0, deflate(tmp))
            end()
            buf.toByteArray()
        }
        out.write("1 0 obj\n<< /Type /XRef /Size 6 /Root 2 0 R /W [1 $f2Width 2] /Filter /FlateDecode ".toByteArray(Charsets.US_ASCII))
        out.write("/Length ${flat.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
        out.write(flat)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
        out.write("startxref\n$xrefStart\n%%EOF\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    fun objStmPdf(): ByteArray {
        val cat = "<< /Type /Catalog /Pages 3 0 R >>".toByteArray(Charsets.US_ASCII)
        val pages = "<< /Type /Pages /Kids [4 0 R] /Count 1 >>".toByteArray(Charsets.US_ASCII)
        val page = "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] /Contents 5 0 R >>".toByteArray(Charsets.US_ASCII)
        val contents = "<< /Length 5 >>\nstream\nhello\nendstream".toByteArray(Charsets.US_ASCII)

        val realHeader = StringBuilder()
        var off = 0
        val bodies = listOf(2 to cat, 3 to pages, 4 to page)
        for ((num, body) in bodies) {
            realHeader.append("$num $off ")
            off += body.size
        }
        val hdr = realHeader.toString().toByteArray(Charsets.US_ASCII)
        val stmData = java.io.ByteArrayOutputStream()
        stmData.write(hdr)
        for ((_, body) in bodies) stmData.write(body)
        val stmBytes = stmData.toByteArray()

        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray(Charsets.US_ASCII))
        val contentsOffset = out.size()
        out.write("5 0 obj\n".toByteArray(Charsets.US_ASCII))
        out.write(contents)
        out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        val stmOffset = out.size()
        out.write("6 0 obj\n<< /Type /ObjStm /N 3 /First ${hdr.size} /Length ${stmBytes.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
        out.write(stmBytes)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))

        val xrefStart = out.size()
        val rec = java.io.ByteArrayOutputStream()
        fun writeRec(t: Int, f2: Int, f3: Int) {
            rec.write(t)
            rec.write((f2 ushr 24) and 0xFF); rec.write((f2 ushr 16) and 0xFF)
            rec.write((f2 ushr 8) and 0xFF); rec.write(f2 and 0xFF)
            rec.write((f3 ushr 8) and 0xFF); rec.write(f3 and 0xFF)
        }
        writeRec(0, 0, 65535)
        writeRec(1, xrefStart, 0)
        writeRec(2, 6, 0)
        writeRec(2, 6, 1)
        writeRec(2, 6, 2)
        writeRec(1, contentsOffset, 0)
        writeRec(1, stmOffset, 0)
        val flat = java.util.zip.Deflater().run {
            setInput(rec.toByteArray())
            finish()
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(256)
            while (!finished()) buf.write(tmp, 0, deflate(tmp))
            end()
            buf.toByteArray()
        }
        out.write("1 0 obj\n<< /Type /XRef /Size 7 /Root 2 0 R /W [1 4 2] /Index [0 7] /Filter /FlateDecode ".toByteArray(Charsets.US_ASCII))
        out.write("/Length ${flat.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
        out.write(flat)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
        out.write("startxref\n$xrefStart\n%%EOF\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}
