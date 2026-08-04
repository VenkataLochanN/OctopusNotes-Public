package com.lochan.octopusnotes

object StrokeType {
    const val PEN = "pen"
    const val HIGHLIGHTER = "highlighter"
    const val IMAGE = "image"

    /** Tool types THIS build can render. */
    val SUPPORTED: Set<String> = setOf(PEN, HIGHLIGHTER, IMAGE)
}