package com.example.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Works around a crash in the speech library (sherpa-onnx 1.12.38; fixed upstream in #3527).
 *
 * Moonshine and Whisper split many characters (most Japanese and Chinese ones) into byte
 * pieces, and the library hands every piece to Java as its own string. A lone byte piece is
 * not valid text, so Android stops the app (always in debuggable builds, which check every
 * string passed from native code).
 *
 * Fix: the model loads a copy of its token list in which every byte piece is written as an
 * ASCII marker ("{{#E3}}"), so the library only ever sees valid text. [decodeText] turns runs
 * of markers in the result back into the real characters.
 */
object JniSafeTokens {
    const val SAFE_TOKENS_FILENAME = "tokens.jni-safe.txt"

    private const val MARKER_PREFIX = "{{#"
    private val MARKER = Regex("""\{\{#([0-9A-F]{2})\}\}""")
    private val MARKER_RUN = Regex("""(?:\{\{#[0-9A-F]{2}\}\})+""")
    private val BASE64_TOKEN = Regex("""[A-Za-z0-9+/]+={0,2}""")

    /**
     * Returns the safe copy of [tokens] (written next to it on first use), or [tokens] itself
     * if it is not a base64 token list (then the library decodes no byte pieces from it).
     */
    fun safeTokensFile(tokens: File): File {
        val lines = tokens.readLines()
        if (!isBase64TokenList(lines)) return tokens
        val safe = File(tokens.parentFile, SAFE_TOKENS_FILENAME)
        if (safe.length() > 0 && safe.lastModified() >= tokens.lastModified()) return safe
        val temp = File(tokens.parentFile, "$SAFE_TOKENS_FILENAME.tmp")
        temp.writeText(lines.joinToString(separator = "\n", postfix = "\n") { sanitizeLine(it) })
        safe.delete()
        if (!temp.renameTo(safe)) {
            temp.delete()
            return tokens
        }
        return safe
    }

    /**
     * Every line is "<base64 token> <id>", as in Moonshine and Whisper token lists. The token
     * may be empty (Moonshine has one: " 31353").
     */
    internal fun isBase64TokenList(lines: List<String>): Boolean {
        val tokenLines = lines.filter { it.isNotBlank() }
        return tokenLines.isNotEmpty() && tokenLines.all { line ->
            val space = line.lastIndexOf(' ')
            val token = if (space >= 0) line.substring(0, space) else return@all false
            val id = line.substring(space + 1)
            id.isNotEmpty() && id.all { it.isDigit() } &&
                (token.isEmpty() || (token.length % 4 == 0 && BASE64_TOKEN.matches(token)))
        }
    }

    /** Rewrites one "<base64 token> <id>" line; a token that is not valid text becomes markers. */
    internal fun sanitizeLine(line: String): String {
        val space = line.lastIndexOf(' ')
        if (space <= 0) return line
        val bytes = try {
            Base64.getDecoder().decode(line.substring(0, space))
        } catch (e: IllegalArgumentException) {
            return line
        }
        if (isValidUtf8(bytes)) return line
        val markers = bytes.joinToString(separator = "") { "$MARKER_PREFIX%02X}}".format(it.toInt() and 0xFF) }
        return Base64.getEncoder().encodeToString(markers.toByteArray(Charsets.US_ASCII)) + line.substring(space)
    }

    /** Turns runs of byte markers in recognized text back into characters. */
    fun decodeText(text: String): String {
        if (!text.contains(MARKER_PREFIX)) return text
        return MARKER_RUN.replace(text) { run ->
            val bytes = MARKER.findAll(run.value).map { it.groupValues[1].toInt(16).toByte() }.toList().toByteArray()
            // A piece the model never completed is dropped
            String(bytes, Charsets.UTF_8).replace("�", "")
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}
