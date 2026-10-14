package com.example

import com.example.overlay.SubtitleStyle
import com.example.ui.components.ROLL_UP_HISTORY
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
        for (id in 1L..(ROLL_UP_HISTORY + 10L)) lines = updateRollLines(lines, id, "Line $id", isFinal = true)
        assertEquals(ROLL_UP_HISTORY, lines.size)
        assertEquals("Line ${ROLL_UP_HISTORY + 10}", lines.last().text)
    }

    @Test
    fun savedStyleCodesMapToStyles() {
        assertEquals(SubtitleStyle.MOVIE, SubtitleStyle.fromCode(0))
        assertEquals(SubtitleStyle.ROLL_UP, SubtitleStyle.fromCode(1))
        assertEquals(SubtitleStyle.LIVE, SubtitleStyle.fromCode(2))
        assertEquals(SubtitleStyle.ROLL_UP, SubtitleStyle.fromCode(99))
    }
}
