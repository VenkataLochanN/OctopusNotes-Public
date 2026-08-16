package com.lochan.octopusnotes

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StylusToolSwitcherTest {

    private class Harness(initial: String?) {
        var active: String? = initial
        val calls = mutableListOf<String>()
        val switcher = StylusToolSwitcher(
            activateTool = { calls.add(it); active = it },
            currentToolId = { active }
        )
    }

    @Test
    fun clickTwiceRevertsToLastUsed() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)
        assertEquals(listOf("HIGHLIGHTER"), h.calls)

        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("PEN", h.active)
        assertEquals(listOf("HIGHLIGHTER", "PEN"), h.calls)
    }

    @Test
    fun repeatedClicksAlternate() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("PEN", h.active)
    }

    @Test
    fun manualSwitchBetweenClicksStillToggles() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)

        h.active = "ERASER"
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("ERASER", h.active)
    }

    @Test
    fun lastUsedFallsBackToPenWhenNeverSwitched() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.LAST_USED)
        assertEquals("PEN", h.active)
    }

    @Test
    fun lastUsedTogglesToPreviousStylusTool() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.ERASER)
        assertEquals("ERASER", h.active)
        h.switcher.apply(StylusAction.LAST_USED)
        assertEquals("PEN", h.active)
    }

    @Test
    fun nullCurrentStillSwitchesAndRemembersLater() {
        val h = Harness(null)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("PEN", h.active)
    }

    @Test
    fun manualDockSelectionIsRememberedAsLastUsed() {
        val h = Harness("ERASER")

        h.switcher.onToolActivated("HIGHLIGHTER")
        h.active = "HIGHLIGHTER"

        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("ERASER", h.active)
        assertEquals(listOf("ERASER"), h.calls)
    }

    @Test
    fun manualSwitchBetweenStylusClicksDrivesTheRevert() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)

        h.switcher.onToolActivated("ERASER")
        h.active = "ERASER"
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("ERASER", h.active)
    }

    @Test
    fun reactivatingSameToolDoesNotClobberLastUsed() {
        val h = Harness("PEN")
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("HIGHLIGHTER", h.active)

        h.switcher.onToolActivated("HIGHLIGHTER")
        h.switcher.apply(StylusAction.HIGHLIGHTER)
        assertEquals("PEN", h.active)
    }

    @Test
    fun xiaomiPageUpDownKeycodesMapToBarrelButtons() {
        assertEquals(StylusBarrelButton.PRIMARY, StylusBarrelInput.buttonForKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(StylusBarrelButton.SECONDARY, StylusBarrelInput.buttonForKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun androidStylusButtonKeycodesMapToBarrelButtons() {
        assertEquals(StylusBarrelButton.PRIMARY, StylusBarrelInput.buttonForKeyCode(308))
        assertEquals(StylusBarrelButton.SECONDARY, StylusBarrelInput.buttonForKeyCode(309))
        assertEquals(StylusBarrelButton.SECONDARY, StylusBarrelInput.buttonForKeyCode(310))
        assertEquals(StylusBarrelButton.SECONDARY, StylusBarrelInput.buttonForKeyCode(311))
    }

    @Test
    fun unrelatedKeycodesAreNotBarrelButtons() {
        assertNull(StylusBarrelInput.buttonForKeyCode(KeyEvent.KEYCODE_A))
        assertNull(StylusBarrelInput.buttonForKeyCode(KeyEvent.KEYCODE_BACK))
    }
}
