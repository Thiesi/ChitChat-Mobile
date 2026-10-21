package io.github.thiesi.chitchat.core.ui

/** Preserve complete Unicode code points in short, plain-text message previews. */
internal fun messageExcerpt(text: String): String =
    text.substring(0, text.offsetByCodePoints(0, minOf(160, text.codePointCount(0, text.length))))
