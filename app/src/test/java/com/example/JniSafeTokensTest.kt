package com.example

import com.example.asr.JniSafeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** Byte pieces in Moonshine/Whisper token lists must never reach the library as broken text. */
class JniSafeTokensTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decoded(line: String) = String(Base64.getDecoder().decode(line.substringBefore(' ')), Charsets.UTF_8)

    @Test
    fun wholeCharactersAreKeptAndBytePiecesBecomeMarkers() {
        // "▁" (a whole character) stays as it is
        assertEquals("4paB 301", JniSafeTokens.sanitizeLine("4paB 301"))
        // A lone 0xE3 (the first byte of most Japanese characters) becomes a marker
        val lone = JniSafeTokens.sanitizeLine("${b64(byteArrayOf(0xE3.toByte()))} 230")
        assertEquals("{{#E3}}", decoded(lone))
        assertTrue(lone.endsWith(" 230"))
        // A token mixing a byte piece with a letter is written all as markers
        assertEquals("{{#82}}{{#61}}", decoded(JniSafeTokens.sanitizeLine("${b64(byteArrayOf(0x82.toByte(), 'a'.code.toByte()))} 7")))
    }

    @Test
    fun markersInTheResultBecomeCharactersAgain() {
        // あ = E3 81 82, split over two tokens
        assertEquals("駅はあ。", JniSafeTokens.decodeText("駅は{{#E3}}{{#81}}{{#82}}。"))
        assertEquals("hello", JniSafeTokens.decodeText("hello"))
        // An unfinished piece is dropped
        assertEquals("駅", JniSafeTokens.decodeText("駅{{#E3}}"))
    }

    @Test
    fun safeCopyIsWrittenOnlyForBase64TokenLists() {
        // Moonshine's list has one empty token (" 31353")
        val base64List = temp.newFile("tokens.txt").apply {
            writeText("PHVuaz4= 0\n4w== 1\n4paB 2\n 3\n")
        }
        val safe = JniSafeTokens.safeTokensFile(base64List)
        assertEquals(JniSafeTokens.SAFE_TOKENS_FILENAME, safe.name)
        val lines = safe.readLines()
        assertEquals("PHVuaz4= 0", lines[0])
        assertEquals("{{#E3}}", decoded(lines[1]))
        assertEquals("4paB 2", lines[2])

        // A plain-text list (SenseVoice, transducers) is used as it is
        val dir = temp.newFolder("plain")
        val plain = java.io.File(dir, "tokens.txt").apply { writeText("<blk> 0\n▁the 1\nabcd 2\n") }
        assertEquals(plain, JniSafeTokens.safeTokensFile(plain))
        assertFalse(java.io.File(dir, JniSafeTokens.SAFE_TOKENS_FILENAME).exists())
    }
}
