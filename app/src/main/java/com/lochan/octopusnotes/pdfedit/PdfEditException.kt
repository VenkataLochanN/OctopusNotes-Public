package com.lochan.octopusnotes.pdfedit

sealed class PdfEditException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unsupported(message: String) : PdfEditException(message)
    class Corrupt(message: String, cause: Throwable? = null) : PdfEditException(message, cause)
}
