package com.lochan.octopusnotes.pdfedit

class PdfLexer(private val data: ByteArray, pos: Int = 0) {

    private var pos = pos

    class Token(val type: Type, val text: String = "", val bytes: ByteArray? = null) {
        enum class Type { NUMBER, NAME, STRING, KEYWORD, OPEN_DICT, CLOSE_DICT, OPEN_ARRAY, CLOSE_ARRAY, EOF }
        override fun toString(): String = "$type($text)"
    }

    val position: Int get() = pos

    fun advance(n: Int) {
        pos += n
    }

    private fun peek(): Int = if (pos < data.size) data[pos].toInt() and 0xFF else -1

    private fun isDelimiter(c: Int): Boolean = when (c) {
        '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code, '{'.code, '}'.code, '/'.code, '%'.code -> true
        else -> false
    }

    private fun isWhitespace(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

    fun skipWsAndComments() {
        while (true) {
            while (pos < data.size && isWhitespace(peek())) pos++
            if (pos < data.size && data[pos] == '%'.code.toByte()) {
                while (pos < data.size && data[pos] != '\n'.code.toByte()) pos++
            } else break
        }
    }

    fun next(): Token {
        skipWsAndComments()
        if (pos >= data.size) return Token(Token.Type.EOF)
        val c = peek()
        return when {
            c == '<'.code -> {
                if (pos + 1 < data.size && data[pos + 1] == '<'.code.toByte()) {
                    pos += 2
                    Token(Token.Type.OPEN_DICT)
                } else {
                    pos++
                    Token(Token.Type.STRING, bytes = parseHexString())
                }
            }
            c == '>'.code -> {
                if (pos + 1 < data.size && data[pos + 1] == '>'.code.toByte()) {
                    pos += 2
                    Token(Token.Type.CLOSE_DICT)
                } else {
                    throw PdfEditException.Corrupt("stray '>' at offset $pos")
                }
            }
            c == '['.code -> { pos++; Token(Token.Type.OPEN_ARRAY) }
            c == ']'.code -> { pos++; Token(Token.Type.CLOSE_ARRAY) }
            c == '/'.code -> { pos++; Token(Token.Type.NAME, text = parseName()) }
            c == '('.code -> { pos++; Token(Token.Type.STRING, bytes = parseLiteralString()) }
            c in '0'.code..'9'.code || c == '+'.code || c == '-'.code || c == '.'.code ->
                Token(Token.Type.NUMBER, text = parseNumber())
            else -> Token(Token.Type.KEYWORD, text = parseKeyword())
        }
    }

    fun parseValue(): PdfObject {
        val t = next()
        return when (t.type) {
            Token.Type.NUMBER -> {

                val afterFirst = pos
                val n2 = next()
                if (n2.type == Token.Type.NUMBER) {
                    val k = next()
                    if (k.type == Token.Type.KEYWORD && k.text == "R") {
                        return PdfObject.Ref(
                            t.text.toIntOrNull() ?: throw PdfEditException.Corrupt("bad ref number"),
                            n2.text.toIntOrNull() ?: 0
                        )
                    }
                }
                pos = afterFirst
                PdfObject.Number(t.text)
            }
            Token.Type.NAME -> PdfObject.Name(t.text)
            Token.Type.STRING -> PdfObject.Str(t.bytes ?: ByteArray(0))
            Token.Type.OPEN_ARRAY -> parseArrayBody()
            Token.Type.OPEN_DICT -> parseDictBody()
            Token.Type.KEYWORD -> when (t.text) {
                "true" -> PdfObject.Bool(true)
                "false" -> PdfObject.Bool(false)
                "null" -> PdfObject.Null
                else -> throw PdfEditException.Corrupt("unexpected keyword '${t.text}' in value")
            }
            Token.Type.EOF -> throw PdfEditException.Corrupt("unexpected EOF in value")
            else -> throw PdfEditException.Corrupt("unexpected token in value: $t")
        }
    }

    fun parseArrayBody(): PdfObject.ArrayRef {
        val arr = PdfObject.ArrayRef()
        while (true) {
            val nt = peekToken()
            if (nt.type == Token.Type.CLOSE_ARRAY) { next(); break }
            if (nt.type == Token.Type.EOF) throw PdfEditException.Corrupt("unterminated array")
            arr.items.add(parseValue())
        }
        return arr
    }

    fun parseDictBody(): PdfObject.Dict {
        val dict = PdfObject.Dict()
        while (true) {
            val t = next()
            when (t.type) {
                Token.Type.CLOSE_DICT -> return dict
                Token.Type.EOF -> throw PdfEditException.Corrupt("unterminated dict")
                Token.Type.NAME -> dict.map[t.text] = parseValue()
                Token.Type.KEYWORD -> {

                    if (t.text == "endobj") throw PdfEditException.Corrupt("dict ran into endobj")
                }
                else -> throw PdfEditException.Corrupt("unexpected token in dict: $t")
            }
        }
    }

    private fun peekToken(): Token {
        val save = pos
        val t = next()
        pos = save
        return t
    }

    private fun parseName(): String {
        val sb = StringBuilder()
        while (pos < data.size) {
            val c = peek()
            if (isWhitespace(c) || isDelimiter(c)) break
            if (c == '#'.code && pos + 2 < data.size) {
                val h = hexVal(data[pos + 1]) shl 4 or hexVal(data[pos + 2])
                if (h >= 0) {
                    sb.append(h.toChar())
                    pos += 3
                    continue
                }
            }
            sb.append(c.toChar())
            pos++
        }
        return sb.toString()
    }

    private fun hexVal(b: Byte): Int {
        val c = (b.toInt() and 0xFF).toChar().uppercaseChar()
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }

    private fun parseHexString(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (pos < data.size) {
            val c = peek()
            if (c == '>'.code) { pos++; break }
            if (isWhitespace(c)) { pos++; continue }
            val v = hexVal(data[pos].toByte())
            if (v < 0) { pos++; continue }
            pos++
            if (hi < 0) hi = v
            else { out.write((hi shl 4) or v); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun parseLiteralString(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            pos++
            when (c) {
                '\\'.code -> {
                    if (pos >= data.size) break
                    val e = data[pos].toInt() and 0xFF
                    pos++
                    when (e) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '('.code -> out.write('('.code)
                        ')'.code -> out.write(')'.code)
                        '\\'.code -> out.write('\\'.code)
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            var k = 1
                            while (k < 3 && pos < data.size) {
                                val d = data[pos].toInt() and 0xFF
                                if (d !in '0'.code..'7'.code) break
                                v = (v shl 3) or (d - '0'.code)
                                pos++
                                k++
                            }
                            out.write(v and 0xFF)
                        }
                        '\r'.code -> if (pos < data.size && data[pos] == '\n'.code.toByte()) pos++
                        else -> out.write(e)
                    }
                }
                '('.code -> { depth++; out.write('('.code) }
                ')'.code -> {
                    depth--
                    if (depth == 0) break
                    out.write(')'.code)
                }
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    private fun parseNumber(): String {
        val sb = StringBuilder()
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (c in '0'.code..'9'.code || c == '.'.code || c == '+'.code || c == '-'.code) {
                sb.append(c.toChar())
                pos++
            } else break
        }
        return sb.toString()
    }

    private fun parseKeyword(): String {
        val sb = StringBuilder()
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (isWhitespace(c) || isDelimiter(c)) break
            sb.append(c.toChar())
            pos++
        }
        return sb.toString()
    }
}
