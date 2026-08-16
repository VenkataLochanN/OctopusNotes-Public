package com.lochan.octopusnotes.pdfedit

import java.io.ByteArrayOutputStream
import java.io.OutputStream

class PdfDoc private constructor(
    private val data: ByteArray,
    private val xref: PdfXref
) {

    private val changeLog = LinkedHashMap<Int, PdfObject>()

    private val cache = HashMap<Int, PdfObject>()
    private var nextObjNum = xref.maxObjNum + 1

    private val objStmCache = HashMap<Int, Map<Int, PdfObject>>()

    private val rootRef: PdfObject.Ref? = xref.trailer.map["Root"] as? PdfObject.Ref

    val pageCount: Int get() = countPages()

    fun loadRef(ref: PdfObject.Ref): PdfObject {
        val e = xref.entries[ref.num] ?: throw PdfEditException.Unsupported(
            "object ${ref.num} not in classic xref (packed in an object stream?); not supported"
        )
        if (e.isFree) throw PdfEditException.Corrupt("object ${ref.num} is free")
        if (e.isCompressed) return cache.getOrPut(ref.num) { loadCompressedObject(ref.num, e) }
        return cache.getOrPut(ref.num) { loadObjectAt(ref.num, e) }
    }

    private fun loadCompressedObject(num: Int, e: PdfXrefEntry): PdfObject {
        if (e.offset > Int.MAX_VALUE.toLong()) {
            throw PdfEditException.Corrupt("object $num: object-stream number ${e.offset} out of range")
        }
        val stmNum = e.offset.toInt()
        val idx = e.gen
        val objects = objStmCache.getOrPut(stmNum) {
            val stmEntry = xref.entries[stmNum]
                ?: throw PdfEditException.Corrupt("object stream $stmNum not in xref")
            val stm = loadRef(PdfObject.Ref(stmNum, 0)) as? PdfObject.Stream
                ?: throw PdfEditException.Corrupt("object $stmNum is not a stream")
            val bytes = PdfFilters.decode(stm.dict, stm.data, this)
                ?: throw PdfEditException.Corrupt("object stream $stmNum: unsupported filter")
            val n = stm.dict.map["N"]?.asNumber?.intValue?.toInt()
                ?: throw PdfEditException.Corrupt("object stream $stmNum has no /N")
            val first = stm.dict.map["First"]?.asNumber?.intValue?.toInt()
                ?: throw PdfEditException.Corrupt("object stream $stmNum has no /First")

            val lex = PdfLexer(bytes, 0)
            val offsets = HashMap<Int, Int>()
            repeat(n) {
                val o = lex.next()
                val off = lex.next()
                if (o.type != PdfLexer.Token.Type.NUMBER || off.type != PdfLexer.Token.Type.NUMBER) {
                    throw PdfEditException.Corrupt("bad object stream $stmNum header")
                }
                val objNum = o.text.toIntOrNull() ?: throw PdfEditException.Corrupt("bad objstm number")
                offsets[objNum] = off.text.toIntOrNull() ?: 0
            }
            val map = HashMap<Int, PdfObject>()
            for ((objNum, off) in offsets) {
                map[objNum] = PdfLexer(bytes, first + off).parseValue()
            }
            map
        }
        return objects[num] ?: throw PdfEditException.Corrupt("object $num not found in stream $stmNum")
    }

    fun catalogDict(): PdfObject.Dict {
        val root = rootRef ?: throw PdfEditException.Corrupt("trailer has no /Root")
        return (loadRef(root) as? PdfObject.Dict) ?: throw PdfEditException.Corrupt("bad /Root")
    }

    fun pageDict(index: Int): PdfObject.Dict {
        val loc = locatePage(index)
        return (loc.pageItem.deref(this) as? PdfObject.Dict)
            ?: throw PdfEditException.Corrupt("page $index is not a dict")
    }

    fun pageSize(index: Int): Pair<Float, Float> = pageMediaBox(pageDict(index))

    fun indexOfPage(objNum: Int): Int {
        var index = 0
        fun walk(node: PdfObject, depth: Int): Int {
            if (depth > 1000) return -1
            val dict = try { node.deref(this) as? PdfObject.Dict } catch (e: Exception) { return -1 }
                ?: return -1
            if (isIntermediateNode(dict)) {
                val kids = try {
                    dict.map["Kids"]?.deref(this) as? PdfObject.ArrayRef
                } catch (e: Exception) { null } ?: return -1
                for (k in kids.items) {
                    val found = walk(k, depth + 1)
                    if (found >= 0) return found
                }
                return -1
            }
            val ref = node as? PdfObject.Ref
            val matched = ref != null && ref.num == objNum
            val result = if (matched) index else -1
            index++
            return result
        }
        val rootVal = rootPagesNodeValue()
        return try { walk(rootVal.first, 0) } catch (e: Exception) { -1 }
    }

    private fun loadObjectAt(num: Int, e: PdfXrefEntry): PdfObject {

        val off = e.offset
        if (off > Int.MAX_VALUE.toLong() || off >= data.size.toLong()) {
            throw PdfEditException.Corrupt("object $num at offset $off out of range")
        }
        val lex = PdfLexer(data, off.toInt())
        val n1 = lex.next()
        val n2 = lex.next()
        val kw = lex.next()
        if (n1.type != PdfLexer.Token.Type.NUMBER || n2.type != PdfLexer.Token.Type.NUMBER ||
            kw.type != PdfLexer.Token.Type.KEYWORD || kw.text != "obj"
        ) {
            throw PdfEditException.Corrupt("expected 'N G obj' at offset ${e.offset} for object $num")
        }
        val value = lex.parseValue()
        val after = lex.next()
        return if (after.type == PdfLexer.Token.Type.KEYWORD && after.text == "stream") {
            val dict = value as? PdfObject.Dict ?: throw PdfEditException.Corrupt("stream without dict for $num")
            readStreamData(lex, dict, num)
        } else {
            value
        }
    }

    private fun readStreamData(lex: PdfLexer, dict: PdfObject.Dict, num: Int): PdfObject {

        val p = lex.position
        if (p < data.size && data[p] == '\r'.code.toByte()) {
            lex.advance(1)
            if (lex.position < data.size && data[lex.position] == '\n'.code.toByte()) lex.advance(1)
        } else if (p < data.size && data[p] == '\n'.code.toByte()) {
            lex.advance(1)
        }
        val start = lex.position
        val lenObj = dict.map["Length"] ?: throw PdfEditException.Corrupt("stream $num has no /Length")
        val len = when (lenObj) {
            is PdfObject.Number -> lenObj.intValue?.toInt()
                ?: throw PdfEditException.Corrupt("bad /Length for stream $num")
            is PdfObject.Ref -> {
                val v = loadRef(lenObj)
                (v as? PdfObject.Number)?.intValue?.toInt()
                    ?: throw PdfEditException.Corrupt("indirect /Length for stream $num not a number")
            }
            else -> throw PdfEditException.Corrupt("bad /Length for stream $num")
        }
        if (start + len > data.size) throw PdfEditException.Corrupt("stream $num overruns file")
        return PdfObject.Stream(dict, data.copyOfRange(start, start + len))
    }

    private class Loc(
        val pageItem: PdfObject,
        val kidIndex: Int,
        val parent: PdfObject.Dict,
        val parentRef: PdfObject.Ref?
    )

    private fun locatePage(index: Int): Loc {

        val rootVal = rootPagesNodeValue()
        var node: PdfObject = rootVal.first
        var nodeRef: PdfObject.Ref? = rootVal.second
        var skip = index
        var guard = 0
        while (true) {
            if (guard++ > 1000) throw PdfEditException.Corrupt("page tree too deep or cyclic")
            val dict = node.asDict ?: throw PdfEditException.Corrupt("bad page tree node")
            val kids = dict.map["Kids"]?.deref(this) as? PdfObject.ArrayRef
                ?: throw PdfEditException.Corrupt("page tree node has no /Kids")
            var descended = false
            for (i in kids.items.indices) {
                val item = kids.items[i]
                val kid = item.deref(this)
                if (kid is PdfObject.Dict && isIntermediateNode(kid)) {
                    val count = kid.map["Count"]?.asNumber?.intValue?.toInt() ?: 0
                    if (skip < count) {
                        node = kid
                        nodeRef = item as? PdfObject.Ref
                        descended = true
                        break
                    }
                    skip -= count
                } else {
                    if (skip == 0) {
                        return Loc(item, i, dict, nodeRef)
                    }
                    skip--
                }
            }
            if (!descended) throw PdfEditException.Corrupt("page index $index out of range")
        }
    }

    private fun isIntermediateNode(dict: PdfObject.Dict): Boolean {
        val t = dict.map["Type"]?.asName?.name
        if (t == "Pages") return true
        if (t == "Page") return false
        return dict.map.containsKey("Kids") && !dict.map.containsKey("MediaBox")
    }

    private fun rootPagesNode(): PdfObject.Dict = rootPagesNodeValue().first

    private fun rootPagesNodeValue(): Pair<PdfObject.Dict, PdfObject.Ref?> {
        val root = rootRef ?: throw PdfEditException.Corrupt("trailer has no /Root")
        val catalog = loadRef(root)
        val pages = catalog.asDict?.map?.get("Pages")
            ?: throw PdfEditException.Corrupt("catalog has no /Pages")
        val dict = (pages.deref(this) as? PdfObject.Dict)
            ?: throw PdfEditException.Corrupt("bad /Pages")
        return dict to (pages as? PdfObject.Ref)
    }

    private fun countPages(): Int =
        rootPagesNode().map["Count"]?.asNumber?.intValue?.toInt() ?: 0

    private fun kidsOf(node: PdfObject.Dict): PdfObject.ArrayRef {
        val kidsVal = node.map["Kids"] ?: throw PdfEditException.Corrupt("page tree node has no /Kids")

        if (kidsVal is PdfObject.Ref) mark(kidsVal)
        return kidsVal.deref(this) as? PdfObject.ArrayRef
            ?: throw PdfEditException.Corrupt("page tree node has no /Kids")
    }

    fun mark(ref: PdfObject.Ref) {
        if (ref.num in changeLog) return
        val obj = cache[ref.num] ?: loadRef(ref)
        changeLog[ref.num] = obj
    }

    private fun addObject(obj: PdfObject): PdfObject.Ref {
        val num = nextObjNum++
        changeLog[num] = obj
        return PdfObject.Ref(num, 0)
    }

    private fun markTreeChain(parent: PdfObject.Dict, parentRef: PdfObject.Ref?) {
        if (parentRef != null) mark(parentRef)
        var up: PdfObject? = parent.map["Parent"]
        while (up != null) {
            val r = up as? PdfObject.Ref ?: break
            mark(r)
            up = (up.deref(this) as? PdfObject.Dict)?.map?.get("Parent")
        }
    }

    private fun bumpCountChain(parent: PdfObject.Dict, parentRef: PdfObject.Ref?, delta: Int) {
        var node: PdfObject? = parent
        var ref = parentRef
        while (node != null) {
            val d = node.asDict ?: break
            val c = d.map["Count"]?.asNumber?.intValue?.toInt() ?: 0
            d.map["Count"] = PdfObject.Number((c + delta).toString())
            if (ref != null) mark(ref)
            val up = d.map["Parent"]
            node = up?.deref(this)
            ref = up as? PdfObject.Ref
        }
    }

    private fun markPageItem(pageItem: PdfObject, parent: PdfObject.Dict, parentRef: PdfObject.Ref?) {
        if (pageItem is PdfObject.Ref) mark(pageItem)
        else markTreeChain(parent, parentRef)
    }

    fun rotatePage(index: Int, deltaDeg: Int) {
        val loc = locatePage(index)
        val page = (loc.pageItem.deref(this) as? PdfObject.Dict)
            ?: throw PdfEditException.Corrupt("page $index is not a dict")
        val cur = page.map["Rotate"]?.asNumber?.intValue?.toInt() ?: 0
        val next = (((cur + deltaDeg) % 360) + 360) % 360
        page.map["Rotate"] = PdfObject.Number(next.toString())
        markPageItem(loc.pageItem, loc.parent, loc.parentRef)
    }

    fun deletePages(indicesDesc: List<Int>) {
        for (index in indicesDesc) {
            val loc = locatePage(index)
            val kids = kidsOf(loc.parent)
            if (loc.kidIndex in kids.items.indices) {
                kids.items.removeAt(loc.kidIndex)
            }
            bumpCountChain(loc.parent, loc.parentRef, -1)
        }
    }

    fun movePage(from: Int, to: Int) {
        if (from == to) return

        val loc = locatePage(from)
        val pageItem = loc.pageItem
        val page = loc.pageItem.deref(this) ?: pageItem
        val srcKids = kidsOf(loc.parent)
        if (loc.kidIndex in srcKids.items.indices) {
            srcKids.items.removeAt(loc.kidIndex)
        }
        bumpCountChain(loc.parent, loc.parentRef, -1)

        insertPageAt(to, pageItem, page)
    }

    private fun insertPageAt(index: Int, pageItem: PdfObject, page: PdfObject) {
        val count = countPages()
        val targetLoc: Loc?
        if (count == 0) {
            targetLoc = null
        } else if (index < count) {
            targetLoc = locatePage(index)
        } else {
            targetLoc = locatePage(count - 1)
        }
        val kids: PdfObject.ArrayRef
        val parent: PdfObject.Dict
        val parentRef: PdfObject.Ref?
        val at: Int
        if (targetLoc == null) {
            val root = rootPagesNode()
            parent = root
            parentRef = null
            kids = kidsOf(root)
            at = kids.items.size
        } else {
            parent = targetLoc.parent
            parentRef = targetLoc.parentRef
            kids = kidsOf(parent)
            at = if (index < count) targetLoc.kidIndex else targetLoc.kidIndex + 1
        }

        (page.deref(this) as? PdfObject.Dict)?.map?.set("Parent", parentRef ?: PdfObject.Null)
        kids.items.add(at.coerceIn(0, kids.items.size), pageItem)
        bumpCountChain(parent, parentRef, +1)
        markPageItem(pageItem, parent, parentRef)
    }

    fun duplicatePage(index: Int) {
        val loc = locatePage(index)
        val src = (loc.pageItem.deref(this) as? PdfObject.Dict)
            ?: throw PdfEditException.Corrupt("page $index is not a dict")
        val copy = PdfObject.Dict(LinkedHashMap(src.map))

        copy.map["Parent"] = loc.parentRef ?: PdfObject.Null
        val newRef = addObject(copy)
        val kids = kidsOf(loc.parent)
        kids.items.add(loc.kidIndex + 1, newRef)
        bumpCountChain(loc.parent, loc.parentRef, +1)
    }

    fun insertTemplatePage(at: Int, wPt: Float, hPt: Float, jpeg: ByteArray, jpegW: Int, jpegH: Int) {
        val img = makeImageXObject(jpeg, jpegW, jpegH)
        val resources = PdfObject.Dict().also { res ->
            res.map["XObject"] = PdfObject.Dict().also { xo -> xo.map["Im0"] = img }
        }
        val pageDict = PdfObject.Dict().also { p ->
            p.map["Type"] = PdfObject.Name("Page")
            p.map["MediaBox"] = PdfObject.ArrayRef(mutableListOf(
                PdfObject.Number("0"), PdfObject.Number("0"),
                PdfObject.Number(wPt.toInt().toString()), PdfObject.Number(hPt.toInt().toString())
            ))
            p.map["Resources"] = addObject(resources)
            p.map["Contents"] = addObject(makeContentStream(makeTemplateContent(wPt, hPt, img)))
            p.map["Rotate"] = PdfObject.Number("0")
        }
        val pageRef = addObject(pageDict)
        insertPageAt(at, pageRef, pageDict)
    }

    fun applyTemplate(pages: List<Int>, jpeg: ByteArray, jpegW: Int, jpegH: Int, prepend: Boolean) {
        val img = makeImageXObject(jpeg, jpegW, jpegH)
        for (index in pages) {
            val loc = locatePage(index)
            val page = (loc.pageItem.deref(this) as? PdfObject.Dict)
                ?: throw PdfEditException.Corrupt("page $index is not a dict")
            val mb = pageMediaBox(page)
            val contentRef = addObject(makeContentStream(makeTemplateContent(mb.first, mb.second, img)))

            val newContents = mutableListOf<PdfObject>()
            if (prepend) newContents.add(contentRef)
            val rawContents = page.map["Contents"]
            val existing = rawContents?.deref(this)
            when (existing) {
                is PdfObject.ArrayRef -> newContents.addAll(existing.items)
                is PdfObject.Stream -> newContents.add(rawContents ?: existing)
                null -> {}
                else -> {}
            }
            if (!prepend) newContents.add(contentRef)
            page.map["Contents"] = PdfObject.ArrayRef(newContents)

            val resVal = page.map["Resources"]
            val newRes: PdfObject.Dict = when {
                resVal == null -> PdfObject.Dict()
                resVal is PdfObject.Ref -> {
                    val existingRes = resVal.deref(this) as? PdfObject.Dict
                    if (existingRes == null) PdfObject.Dict()
                    else PdfObject.Dict(LinkedHashMap(existingRes.map))
                }
                else -> (resVal.deref(this) as? PdfObject.Dict) ?: PdfObject.Dict()
            }
            val xo = (newRes.map["XObject"]?.deref(this) as? PdfObject.Dict)
                ?: PdfObject.Dict().also { newRes.map["XObject"] = it }
            xo.map["Im0"] = img
            page.map["Resources"] = if (resVal is PdfObject.Ref) addObject(newRes) else newRes

            markPageItem(loc.pageItem, loc.parent, loc.parentRef)
        }
    }

    fun pageHasFontResources(index: Int): Boolean {
        val loc = locatePage(index)
        val page = (loc.pageItem.deref(this) as? PdfObject.Dict) ?: return false
        val res = page.map["Resources"]?.deref(this) as? PdfObject.Dict ?: return false
        return res.map.containsKey("Font")
    }

    private fun pageMediaBox(page: PdfObject.Dict): Pair<Float, Float> {
        var node: PdfObject? = page
        while (node != null) {
            val d = node.asDict ?: break
            val mb = d.map["MediaBox"]?.deref(this) as? PdfObject.ArrayRef
            if (mb != null && mb.items.size >= 4) {
                val w = mb.items[2].asNumber?.doubleValue
                val h = mb.items[3].asNumber?.doubleValue
                if (w != null && h != null && w > 0 && h > 0) return w.toFloat() to h.toFloat()
            }
            node = d.map["Parent"]?.deref(this)
        }
        return 595f to 842f
    }

    private fun makeImageXObject(jpeg: ByteArray, w: Int, h: Int): PdfObject.Ref {
        val dict = PdfObject.Dict().also { d ->
            d.map["Type"] = PdfObject.Name("XObject")
            d.map["Subtype"] = PdfObject.Name("Image")
            d.map["Width"] = PdfObject.Number(w.toString())
            d.map["Height"] = PdfObject.Number(h.toString())
            d.map["ColorSpace"] = PdfObject.Name("DeviceRGB")
            d.map["BitsPerComponent"] = PdfObject.Number("8")
            d.map["Filter"] = PdfObject.Name("DCTDecode")
            d.map["Length"] = PdfObject.Number(jpeg.size.toString())
        }
        return addObject(PdfObject.Stream(dict, jpeg))
    }

    private fun makeTemplateContent(wPt: Float, hPt: Float, img: PdfObject.Ref): ByteArray {
        val s = "q\n${fmt(wPt)} 0 0 ${fmt(hPt)} 0 0 cm\n/Im0 Do\nQ\n"
        return s.toByteArray(Charsets.US_ASCII)
    }

    private fun makeContentStream(bytes: ByteArray): PdfObject.Stream =
        PdfObject.Stream(PdfObject.Dict().also { it.map["Length"] = PdfObject.Number(bytes.size.toString()) }, bytes)

    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    fun saveIncremental(out: OutputStream) {
        out.write(data)

        val body = ByteArrayOutputStream()
        val offsets = HashMap<Int, Long>()
        for ((num, obj) in changeLog) {
            offsets[num] = data.size.toLong() + body.size().toLong()
            body.write("$num 0 obj\n".toByteArray(Charsets.US_ASCII))
            obj.writeTo(body)
            body.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }
        val xrefStart = data.size.toLong() + body.size().toLong()
        out.write(body.toByteArray())

        val sb = StringBuilder("xref\n")
        val sorted = changeLog.keys.sorted()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            sb.append("${sorted[i]} ${j - i + 1}\n")
            for (k in i..j) {
                sb.append(String.format("%010d %05d n \n", offsets[sorted[k]] ?: 0L, 0))
            }
            i = j + 1
        }
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))

        val t = StringBuilder("trailer\n<< ")
        t.append("/Size ${nextObjNum} ")
        t.append("/Root ")
        t.append(String((xref.trailer.map["Root"] ?: PdfObject.Null).toByteArray(), Charsets.US_ASCII))
        xref.trailer.map["Info"]?.let {
            t.append(" /Info ")
            t.append(String(it.toByteArray(), Charsets.US_ASCII))
        }
        xref.trailer.map["ID"]?.let {
            t.append(" /ID ")
            t.append(String(it.toByteArray(), Charsets.US_ASCII))
        }
        t.append(" /Prev ${xref.startXref} >>\n")
        t.append("startxref\n$xrefStart\n%%EOF\n")
        out.write(t.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    companion object {
        fun open(data: ByteArray): PdfDoc {
            val xref = PdfXref.parse(data)
            val doc = PdfDoc(data, xref)
            doc.rootRef?.let { doc.loadRef(it) }
            return doc
        }
    }
}
