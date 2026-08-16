package com.lochan.octopusnotes

object StrokeType {
    const val PEN = "pen"
    const val HIGHLIGHTER = "highlighter"
    const val IMAGE = "image"
    const val TABLE = "table"
    const val TEXT = "text"
    const val TAPE = "tape"

    val SUPPORTED: Set<String> = setOf(PEN, HIGHLIGHTER, IMAGE, TABLE, TEXT, TAPE)
}