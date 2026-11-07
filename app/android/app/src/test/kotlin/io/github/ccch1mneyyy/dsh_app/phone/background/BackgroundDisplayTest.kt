package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundDisplayTest {
    @Test fun aFailedObservationReportsItsReasonAndInvalidatesOldElementNumbers() {
        for (code in listOf("treeUnavailable", "displayTargetChanged", "displayMissing", "systemOperationDenied")) {
            val display = withLastElement()
            assertNotNull(display.target("b7", 1))

            val failure = assertThrows(IllegalStateException::class.java) {
                display.requireObservation(false, code, null)
            }

            assertEquals(Reasons.of(code), failure.message)
            assertNull(display.target("b7", 1))
            assertNull(display.target("b8", 1))
        }
    }

    @Test fun aFailedObservationPreservesTheExistingExceptionTypeDetail() {
        val display = withLastElement()

        val failure = assertThrows(IllegalStateException::class.java) {
            display.requireObservation(false, "deviceUnavailable", "IllegalAccessException")
        }

        assertEquals(Reasons.of("deviceUnavailable", "IllegalAccessException"), failure.message)
        assertNull(display.target("b7", 1))
    }

    @Test fun aSuccessfulObservationMayProceedToDecodeItsSnapshot() {
        val display = withLastElement()

        display.requireObservation(true, null, null)

        // The decoder, not this response guard, replaces successful snapshots.
        assertNotNull(display.target("b7", 1))
    }

    @Test fun unnumberedScrollRejectsAnOldOrInvalidatedSnapshotBeforeOpeningAChannel() {
        val display = withLastElement()
        BackgroundDisplay::class.java.getDeclaredField("currentPackage").apply {
            isAccessible = true
            set(display, "example.opened")
        }
        fun assertStale(screenId: String) {
            val result = display.perform(PhoneAction.Scroll(screenId, null, PhoneAction.Direction.DOWN))
            assertFalse(result.ok)
            assertEquals(Reasons.of("staleSnapshot"), result.message)
            org.junit.Assert.assertTrue(result.staleSnapshot)
        }
        assertStale("b6")
        assertThrows(IllegalStateException::class.java) {
            display.requireObservation(false, "treeUnavailable", null)
        }
        assertStale("b7")
    }

    @Test fun collectRejectsAnOldOrInvalidatedScreenBeforeItsFirstObservation() {
        val display = withLastElement()
        BackgroundDisplay::class.java.getDeclaredField("currentPackage").apply {
            isAccessible = true
            set(display, "example.opened")
        }
        fun assertStale(id: String) {
            val error = assertThrows(IllegalStateException::class.java) {
                display.collect(id, 1, 2, null)
            }
            assertEquals(Reasons.of("staleSnapshot"), error.message)
        }
        assertStale("b6")
        assertNotNull(display.target("b7", 1))
        assertThrows(IllegalStateException::class.java) {
            display.requireObservation(false, "treeUnavailable", null)
        }
        assertStale("b7")
    }

    @Test fun onePageCollectUsesOnlyTheSelectedScrollableSubtree() {
        val display = withLists()
        val left = display.collect("b7", 2, 1, "right")
        assertEquals(listOf("left", "nested"), left.items)
        assertEquals(1, left.pages)
        assertEquals("pageLimit", left.stopReason)
        assertNull(left.screen)
        assertEquals("untilMatched", display.collect("b7", 2, 1, "nested").stopReason)
        assertEquals(listOf("right"), display.collect("b7", 6, 1, null).items)
        assertNotNull(display.target("b7", 6))
        val empty = display.collect("b7", 9, 1, null)
        assertEquals(emptyList<String>(), empty.items)
        assertEquals(1, empty.pages)
        assertEquals("pageLimit", empty.stopReason)
    }

    @Test fun onePageCollectRejectsMissingAndNonScrollableTargets() {
        val display = withLists()
        for ((id, reason) in listOf(0 to "elementMissing", 99 to "elementMissing", 1 to "inputRejected", 8 to "inputRejected")) {
            val error = assertThrows(IllegalStateException::class.java) { display.collect("b7", id, 1, null) }
            assertEquals(Reasons.of(reason), error.message)
        }
        val stale = assertThrows(IllegalStateException::class.java) { display.collect("b6", 2, 1, null) }
        assertEquals(Reasons.of("staleSnapshot"), stale.message)
        assertNotNull(display.target("b7", 2))
    }

    private fun withLists(): BackgroundDisplay {
        val display = withLastElement()
        BackgroundDisplay::class.java.getDeclaredField("currentPackage").apply {
            isAccessible = true
            set(display, "example.opened")
        }
        val elements = listOf(
            Element(1, "文本", "header", Region.MIDDLE),
            Element(2, "列表", "", Region.MIDDLE, isScrollable = true),
            Element(3, "文本", "left", Region.MIDDLE, indent = 1),
            Element(4, "列表", "left", Region.MIDDLE, indent = 1, isScrollable = true),
            Element(5, "文本", "nested", Region.MIDDLE, indent = 2),
            Element(6, "列表", "", Region.MIDDLE, isScrollable = true),
            Element(7, "文本", "right", Region.MIDDLE, indent = 1),
            Element(8, "列表", "not scrollable", Region.MIDDLE),
            Element(9, "文本", "", Region.MIDDLE, isScrollable = true),
        )
        BackgroundDisplay::class.java.getDeclaredField("lastElements").apply {
            isAccessible = true
            set(display, elements)
        }
        return display
    }

    private fun withLastElement(): BackgroundDisplay {
        val display = BackgroundDisplay(
            channelProvider = { error("response validation must not start a connection") },
            releaseChannelProvider = { error("response validation must not release the display") },
        )
        BackgroundDisplay::class.java.getDeclaredField("lastScreenId").apply {
            isAccessible = true
            set(display, "b7")
        }
        BackgroundDisplay::class.java.getDeclaredField("lastElements").apply {
            isAccessible = true
            set(display, listOf(Element(1, "按钮", "确定", Region.MIDDLE)))
        }
        return display
    }

    @Test fun onlyBackIsSentToTheBackgroundScreen() {
        val display = BackgroundDisplay(
            channelProvider = { error("a refused key must not reach the privileged channel") },
            releaseChannelProvider = { error("a refused key must not release the background screen") },
        )
        // An app is open on the background screen (set directly: opening one needs the channel).
        BackgroundDisplay::class.java.getDeclaredField("currentPackage").apply {
            isAccessible = true
            set(display, "example.opened")
        }

        for (key in PhoneAction.GlobalKey.entries - PhoneAction.GlobalKey.BACK) {
            val result = display.perform(PhoneAction.Key("b1", key))
            assertFalse(key.name, result.ok)
            assertEquals("后台屏不支持该系统键，未执行；请用返回或打开指定应用", result.message)
        }
    }
}
