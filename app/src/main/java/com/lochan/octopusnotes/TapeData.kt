package com.lochan.octopusnotes

data class TapeData(
    val pattern: String = TapePattern.SOLID,
    val hollow: Boolean = false
)

object TapePattern {
    const val SOLID = "SOLID"
    const val STRIPES = "STRIPES"
    const val DOTS = "DOTS"
    const val GRID = "GRID"
    const val CHECKS = "CHECKS"
}
