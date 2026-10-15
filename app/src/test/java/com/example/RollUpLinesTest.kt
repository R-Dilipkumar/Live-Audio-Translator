package com.example

import com.example.overlay.SubtitleStyle
import com.example.overlay.DEFAULT_ROLL_UP_HISTORY
import com.example.ui.components.RollLine
import com.example.ui.components.updateRollLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** Roll-up subtitle lines: drafts update in place, new sentences stack, history is capped. */
class RollUpLinesTest {

    @Test
    fun draftGrowsInPlaceThenBecomesFinal() {
        var lines = updateRollLines(emptyList(), 5, "Where is", isFinal = false)
        lines = updateRollLines(lines, 5, "Where is the station", isFinal = false)
        lines = updateRollLines(lines, 5, "Where is the station?", isFinal = true)
        assertEquals(listOf(RollLine(5, "Where is the station?", true)), lines)
    }

    @Test
    fun newSentencesStackAndLateDraftsNeverReplaceFinals() {
        var lines = updateRollLines(emptyList(), 5, "Excuse me.", isFinal = true)
        lines = updateRollLines(lines, 6, "Where is", isFinal = false)
        // A late draft of the finished line is ignored
        lines = updateRollLines(lines, 5, "Excuse", isFinal = false)
        assertEquals(listOf("Excuse me.", "Where is"), lines.map { it.text })
        // A line older than everything on screen is too late to show
        lines = updateRollLines(lines, 2, "Old", isFinal = true)
        assertEquals(2, lines.size)
    }

    @Test
    fun historyIsCappedSoScrollingBackStaysLight() {
        var lines = emptyList<RollLine>()
        for (id in 1L..(DEFAULT_ROLL_UP_HISTORY + 10L)) lines = updateRollLines(lines, id, "Line $id", isFinal = true)
        assertEquals(DEFAULT_ROLL_UP_HISTORY, lines.size)
        assertEquals("Line ${DEFAULT_ROLL_UP_HISTORY + 10}", lines.last().text)
    }

    @Test
    fun scrollBackLimitIsTheUsersChoice() {
        var lines = emptyList<RollLine>()
        for (id in 1L..30L) lines = updateRollLines(lines, id, "Line $id", isFinal = true, limit = 10)
        assertEquals(10, lines.size)
        assertEquals("Line 21", lines.first().text)
        // A draft growing in place doesn't push anything out
        lines = updateRollLines(lines, 31, "Line", isFinal = false, limit = 10)
        lines = updateRollLines(lines, 31, "Line 31", isFinal = false, limit = 10)
        assertEquals(10, lines.size)
        assertEquals("Line 31", lines.last().text)
        // A nonsense limit still keeps the newest line
        assertEquals(1, updateRollLines(lines, 32, "Line 32", isFinal = true, limit = 0).size)
    }

    @Test
    fun savedStyleCodesMapToStyles() {
        assertEquals(SubtitleStyle.MOVIE, SubtitleStyle.fromCode(0))
        assertEquals(SubtitleStyle.ROLL_UP, SubtitleStyle.fromCode(1))
        assertEquals(SubtitleStyle.LIVE, SubtitleStyle.fromCode(2))
        assertEquals(SubtitleStyle.ROLL_UP, SubtitleStyle.fromCode(99))
    }
}
