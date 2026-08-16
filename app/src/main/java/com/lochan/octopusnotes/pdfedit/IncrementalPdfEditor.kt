package com.lochan.octopusnotes.pdfedit

import java.io.File
import java.io.FileOutputStream

class IncrementalPdfEditor private constructor(private val doc: PdfDoc) {

    val pageCount: Int get() = doc.pageCount

    fun pageSize(index: Int): Pair<Float, Float> {
        checkIndex(index)
        return doc.pageSize(index)
    }

    fun rotatePage(index: Int, deltaDeg: Int) {
        checkIndex(index)
        doc.rotatePage(index, deltaDeg)
    }

    fun deletePages(indicesDesc: List<Int>) {
        for (i in indicesDesc) checkIndex(i)
        doc.deletePages(indicesDesc)
    }

    fun movePage(from: Int, to: Int) {
        checkIndex(from)
        checkIndex(to)
        doc.movePage(from, to)
    }

    fun duplicatePage(index: Int) {
        checkIndex(index)
        doc.duplicatePage(index)
    }

    fun insertTemplatePage(at: Int, wPt: Float, hPt: Float, jpeg: ByteArray, jpegW: Int, jpegH: Int) {
        doc.insertTemplatePage(at.coerceIn(0, doc.pageCount), wPt, hPt, jpeg, jpegW, jpegH)
    }

    fun applyTemplate(pages: List<Int>, jpeg: ByteArray, jpegW: Int, jpegH: Int, prepend: Boolean) {
        for (i in pages) checkIndex(i)
        doc.applyTemplate(pages, jpeg, jpegW, jpegH, prepend)
    }

    fun pageHasFontResources(index: Int): Boolean {
        checkIndex(index)
        return doc.pageHasFontResources(index)
    }

    fun save(tmp: File) {
        FileOutputStream(tmp).use { doc.saveIncremental(it) }
    }

    private fun checkIndex(i: Int) {
        if (i !in 0 until doc.pageCount) throw PdfEditException.Corrupt("page index $i out of range")
    }

    companion object {

        fun open(file: File): IncrementalPdfEditor = IncrementalPdfEditor(PdfDoc.open(file.readBytes()))
    }
}
