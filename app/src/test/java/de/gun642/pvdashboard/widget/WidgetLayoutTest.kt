package de.gun642.pvdashboard.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutTest {
    @Test
    fun smallWidgetShowsOnlyMainValues() {
        val l = WidgetLayout.of(240f, 110f)
        assertFalse(l.shareBar)
        assertFalse(l.battery)
        assertFalse(l.grid)
        // zwei Werte à „6,02 kW“ müssen nebeneinander passen
        assertTrue(l.bigTextDp * 4.4f <= (240f - 42f) / 2 + 0.01f)
    }

    @Test
    fun moreHeightAddsSections() {
        val mid = WidgetLayout.of(240f, 150f)
        assertTrue(mid.shareBar)
        assertTrue(mid.battery)
        assertFalse(mid.shareText)
        val full = WidgetLayout.of(320f, 260f)
        assertTrue(full.shareText)
        assertTrue(full.grid)
        assertTrue(full.fourColumns)
        assertTrue(full.bigTextDp <= 30f)
    }
}
