package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual PhoneCore -> worker -> gate -> driver path; Android device interfaces are fakes. */
class PhoneCorePromptOwnerTest {
    private val tasks = PhoneTasks()
    private var confirm: () -> Decision = { Decision.ALLOW_ONCE }
    private var handoff: () -> HandoffResult = { HandoffResult.CONTINUE }
    private var beforeHandoff: () -> Unit = {}
    private var asked = 0
    private var lookup: () -> Unit = {}
    private var prepareSystem: () -> Unit = {}
    private var afterStart: () -> Unit = {}
    private var settle: () -> Unit = {}
    private var launchResult = SysResult(true, "launched")
    private var launches = 0
    private var urls = 0
    private var intents = 0
    private val main = Driver(PhoneScreen.MAIN)
    private val background = Driver(PhoneScreen.BACKGROUND)
    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest): Decision { asked++; return confirm() }
            override fun handoff(reason: String, timeoutMs: Long): HandoffResult { asked++; return handoff() }
            override fun status(text: String?) {}
        }
        override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) main else background
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun beforeHandoff(screen: PhoneScreen): String? { beforeHandoff(); return null }
        override fun sleep(ms: Long) { settle() }
        override fun system() = object : SystemApi {
            override fun apps(): List<AppEntry> { lookup(); return listOf(AppEntry("Example", "example.app")) }
            override fun isInstalled(pkg: String) = true
            override fun launch(app: AppEntry, dispatch: PhoneTasks.SystemDispatch): SysResult { commit(dispatch); launches++; afterStart(); return launchResult }
            override fun urlTarget(url: String): UrlTarget {
                lookup()
                return UrlTarget(packageName = "example.app", appLabel = "Example")
            }
            override fun openUrl(url: String, target: UrlTarget, dispatch: PhoneTasks.SystemDispatch): SysResult { commit(dispatch); urls++; afterStart(); return SysResult(true, "opened") }
            override fun intent(spec: IntentSpec, shareTo: AppEntry?, dispatch: PhoneTasks.SystemDispatch): SysResult { commit(dispatch); intents++; afterStart(); return SysResult(true, "sent") }
            override fun notify(title: String, text: String) = error("unexpected notification")
            override fun clipboardSet(text: String) = error("unexpected clipboard")
            override fun clipboardGet() = error("unexpected clipboard")
        }
    }, tasks, ActionLog())

    private class Driver(override val screen: PhoneScreen) : PhoneDriver {
        var reads = 0
        var writes = 0
        var opens = 0
        var observing: () -> Unit = {}
        var observedText = "Example"
        var observedImage: ByteArray? = null
        var collects = 0
        var collecting: () -> Unit = {}
        var collectScreen = true
        val actions = mutableListOf<PhoneAction>()
        override fun availability() = Availability(true)
        override fun foregroundPackage() = "example.app"
        override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
            reads++
            observing()
            return ScreenSnapshot("${screen.prefix}1", observedText, "Example", "example.app", image = observedImage)
        }
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
            collects++
            collecting()
            val snapshot = if (collectScreen) ScreenSnapshot("${screen.prefix}2", "late collected screen",
                "Example", "example.app", image = byteArrayOf(1, 2, 3), imageMime = "image/png") else null
            return CollectResult(listOf("late collected item"), 1, "pageLimit", snapshot)
        }
        override fun target(screenId: String, elementId: Int) = TargetInfo(screenId, elementId,
            "按钮", "发送", "example.app", "Example", false, false, false)
        override fun perform(action: PhoneAction): ActionResult { actions += action; writes++; return ActionResult(true, "done") }
        override fun open(packageName: String): ActionResult { opens++; return ActionResult(true, "opened") }
    }

    // Models the mandatory SystemApi ticket contract, not Android PM or Binder execution.
    private fun commit(dispatch: PhoneTasks.SystemDispatch) {
        prepareSystem()
        if (!dispatch.commitStart()) throw InterruptedException(PhoneTasks.STOPPED_MESSAGE)
    }

    private fun replace() { tasks.endTask(); tasks.begin() }
    private fun handoffCall() = core.call(PhoneToolCatalog.HANDOFF, mapOf("reason" to "User input"))
    private fun tapCall() = core.call(PhoneToolCatalog.TAP, mapOf("screen" to "s1", "id" to 1))
    @After fun close() = core.worker.shutdown()

    private fun openApp() = core.call(PhoneToolCatalog.OPEN, mapOf("app" to "example.app"))
    private fun openUrl() = core.call(PhoneToolCatalog.OPEN, mapOf("url" to "https://example.com"))
    private fun shareIntent() = core.call(PhoneToolCatalog.INTENT,
        mapOf("kind" to "share", "app" to "example.app", "text" to "example"))

    private fun collectCall(screen: String) = core.call(PhoneToolCatalog.COLLECT,
        mapOf("screen" to screen, "list" to 1, "max_pages" to 3))

    private fun assertCollectStopped(result: ToolOutput) {
        assertTrue(result.text, result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertFalse(result.text.contains("late collected item"))
        assertFalse(result.text.contains("late collected screen"))
        assertNull(result.image)
        assertEquals("stopped", core.log.last()?.decision)
    }

    @Test fun aStopDuringCollectDiscardsItsPartialItemsAndScreen() {
        background.collecting = { tasks.stop("stopped while collecting") }
        assertCollectStopped(collectCall("b1"))
        assertEquals(1, background.collects)
        assertTrue(tasks.isStopped())
    }

    @Test fun aReplacedCollectCannotPublishItsContentToTheSuccessor() {
        main.collecting = { replace(); tasks.setApp("successor") }
        assertCollectStopped(collectCall("s1"))
        assertEquals(1, main.collects)
        assertEquals("successor", tasks.snapshot().app)
        assertTrue(tasks.isActive)
        assertFalse(tasks.isStopped())
    }

    @Test fun clearingAStopDoesNotReviveTheOldCollectResult() {
        background.collecting = { tasks.stop("stop"); tasks.clearStop(); tasks.begin() }
        assertCollectStopped(collectCall("b1"))
        assertEquals(1, background.collects)
        assertTrue(tasks.isActive)
        assertFalse(tasks.isStopped())
    }

    @Test fun aCurrentCollectKeepsItsItemsAndOptionalScreen() {
        val mainResult = collectCall("s1")
        assertFalse(mainResult.text, mainResult.isError)
        assertTrue(mainResult.text.contains("late collected item"))
        assertTrue(mainResult.text.contains("late collected screen"))
        assertArrayEquals(byteArrayOf(1, 2, 3), mainResult.image)
        assertEquals("ok", core.log.last()?.decision)
        background.collectScreen = false
        val backgroundResult = collectCall("b1")
        assertFalse(backgroundResult.text, backgroundResult.isError)
        assertTrue(backgroundResult.text.contains("late collected item"))
        assertNull(backgroundResult.image)
        assertEquals(1, main.collects)
        assertEquals(1, background.collects)
    }

    @Test fun replacedTaskCannotLaunchAnApplicationAfterLookupReturns() {
        lookup = ::replace
        val result = openApp()
        assertTrue(result.text, result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertEquals(0, launches)
        assertTrue(tasks.isActive)
    }

    @Test fun clearingAStopDoesNotLetTheOldUrlLookupLaunchUnderANewTask() {
        lookup = { tasks.stop("stop"); tasks.clearStop(); tasks.begin() }
        val result = openUrl()
        assertTrue(result.text, result.isError)
        assertEquals(0, urls)
        assertTrue(tasks.isActive)
        assertFalse(tasks.isStopped())
    }

    @Test fun aStopDuringAppLookupPreventsTheIntentDispatch() {
        lookup = { tasks.stop("stop") }
        val result = shareIntent()
        assertTrue(result.text, result.isError)
        assertEquals(0, intents)
        assertTrue(tasks.isStopped())
    }

    @Test fun anInterruptedLookupCannotDispatchEvenWhileItsOwnerIsCurrent() {
        lookup = { Thread.currentThread().interrupt() }
        val result = shareIntent()
        assertTrue(result.text, result.isError)
        assertEquals(0, intents)
        assertTrue(tasks.isActive)
        assertFalse(tasks.isStopped())
    }

    @Test fun originalOwnerReachesAllThreeFinalSystemEntries() {
        prepareSystem = ::replace
        assertTrue(openApp().isError)
        assertTrue(openUrl().isError)
        assertTrue(shareIntent().isError)
        assertEquals(0, launches + urls + intents)
    }

    @Test fun aCommittedLateStartCannotRenameOrReadTheSuccessor() {
        afterStart = { replace(); tasks.setApp("successor") }
        assertTrue(openApp().isError)
        assertEquals(1, launches)
        assertEquals("successor", tasks.snapshot().app)
        assertEquals(0, main.reads)
    }

    @Test fun currentSystemLaunchesStillDispatchExactlyOnce() {
        assertFalse(openApp().isError)
        assertFalse(openUrl().isError)
        assertFalse(shareIntent().isError)
        assertEquals(1, launches)
        assertEquals(1, urls)
        assertEquals(1, intents)
    }

    @Test fun currentHandoffContinuesAndReadsTheCurrentScreen() {
        assertFalse(handoffCall().isError)
        assertEquals(1, asked)
        assertEquals(1, main.reads)
    }

    @Test fun replacedTaskCannotContinueOldHandoffOrReadItsSuccessor() {
        handoff = { replace(); HandoffResult.CONTINUE }
        assertTrue(handoffCall().isError)
        assertEquals(0, main.reads)
        assertTrue(tasks.isActive)
    }

    @Test fun oldHandoffStopCannotStopItsSuccessor() {
        handoff = { replace(); HandoffResult.STOP }
        assertTrue(handoffCall().isError)
        assertTrue(tasks.isActive)
        assertFalse(tasks.isStopped())
    }

    @Test fun replacementDuringBeforeHandoffCannotAskUnderNewTask() {
        beforeHandoff = ::replace
        assertTrue(handoffCall().isError)
        assertEquals(0, asked)
    }

    @Test fun lateConfirmationCannotWriteIntoSuccessor() {
        confirm = { replace(); Decision.ALLOW_ONCE }
        assertTrue(tapCall().isError)
        assertEquals(1, asked)
        assertEquals(0, main.writes)
        assertTrue(tasks.isActive)
    }

    @Test fun lateBackgroundMoveConfirmationCannotMoveApplication() {
        confirm = { replace(); Decision.ALLOW_ONCE }
        assertTrue(core.call(PhoneToolCatalog.OPEN, mapOf("app" to "example.app", "background" to true)).isError)
        assertEquals(1, asked)
        assertEquals(0, background.opens)
        assertTrue(tasks.isActive)
    }

    @Test fun currentConfirmedWriteStillExecutesExactlyOnce() {
        assertFalse(tapCall().isError)
        assertEquals(1, asked)
        assertEquals(1, main.writes)
    }

    @Test fun missingTapXYObservationNamesTheActualRequiredParameter() {
        val result = core.call(PhoneToolCatalog.TAP_XY, mapOf("x" to 1, "y" to 2))
        assertTrue(result.isError)
        assertEquals("缺少 observation（屏幕号，如 s7）。先调用 screen 拿到屏幕号。", result.text)
        assertEquals(0, asked)
        assertEquals(0, main.writes)
    }

    @Test fun screenIsNotAnAliasForTapXYObservation() {
        val result = core.call(PhoneToolCatalog.TAP_XY, mapOf("screen" to "s1", "x" to 1, "y" to 2))
        assertTrue(result.isError)
        assertEquals("缺少 observation（屏幕号，如 s7）。先调用 screen 拿到屏幕号。", result.text)
        assertEquals(0, asked)
        assertEquals(0, main.writes)
    }

    @Test fun otherRequiredScreenArgumentsKeepTheirExistingName() {
        for (tool in listOf(PhoneToolCatalog.TAP, PhoneToolCatalog.TYPE, PhoneToolCatalog.SCROLL, PhoneToolCatalog.COLLECT)) {
            val result = core.call(tool, mapOf("observation" to "s1"))
            assertTrue(result.isError)
            assertEquals("缺少 screen（屏幕号，如 s7）。先调用 screen 拿到屏幕号。", result.text)
        }
        assertEquals(0, asked)
        assertEquals(0, main.writes)
    }

    @Test fun validObservationKeepsScreenRoutingCoordinatesAndConfirmation() {
        for ((sid, driver) in listOf("s1" to main, "b1" to background)) {
            val before = asked
            val result = core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to sid, "x" to 11, "y" to 22))
            assertFalse(result.isError)
            assertEquals(before + 1, asked)
            assertEquals(listOf(PhoneAction.TapXY(sid, 11, 22)), driver.actions)
            assertEquals(1, driver.writes)
        }
    }
    private fun replaceDuringNextBegin() {
        var armed = true
        tasks.addListener { snapshot ->
            if (armed && snapshot.active) {
                armed = false
                tasks.stop("listener replaced the original begin")
                tasks.clearStop()
                tasks.begin()
                tasks.setApp("successor")
            }
        }
    }

    @Test fun beginListenerCannotLendSuccessorOwnerToOldLaunch() {
        replaceDuringNextBegin()
        val result = openApp()
        assertTrue(result.text, result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertEquals(0, launches)
        assertEquals(0, main.reads)
        assertEquals("successor", tasks.snapshot().app)
    }

    @Test fun beginListenerCannotLendSuccessorOwnerToOldWrite() {
        replaceDuringNextBegin()
        val result = tapCall()
        assertTrue(result.text, result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertEquals(0, asked)
        assertEquals(0, main.writes)
        assertEquals("successor", tasks.snapshot().app)
    }

    @Test fun launchObservationStoppedWhileReadingDiscardsOldScreen() {
        main.observedText = "late private screen"
        main.observedImage = byteArrayOf(1, 2, 3)
        main.observing = { tasks.stop("stopped while observing") }
        val result = openApp()
        assertTrue(result.text, result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertFalse(result.text.contains("late private screen"))
        assertTrue(result.text.contains("launched"))
        assertNull(result.image)
        assertEquals("stopped", core.log.last()!!.decision)
        assertTrue(core.log.last()!!.summary.contains("系统返回成功"))
        assertEquals(1, launches)
        assertEquals(1, main.reads)
    }

    @Test fun lateScreenCannotRenameReplacementTaskOrReturnItsContent() {
        main.observedText = "late private screen"
        main.observing = { replace(); tasks.setApp("successor") }
        val result = core.call(PhoneToolCatalog.SCREEN, emptyMap())
        assertTrue(result.text, result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertFalse(result.text.contains("late private screen"))
        assertEquals("successor", tasks.snapshot().app)
        assertEquals(1, main.reads)
    }

    @Test fun appLabelListenerReplacementPreventsPostLaunchObservation() {
        var armed = true
        tasks.addListener { snapshot ->
            if (armed && snapshot.app == "Example") {
                armed = false
                replace()
                tasks.setApp("successor")
            }
        }
        val result = openApp()
        assertTrue(result.text, result.isError)
        assertEquals(1, launches)
        assertEquals(0, main.reads)
        assertEquals("successor", tasks.snapshot().app)
    }

    @Test fun stoppingDuringSettlePreservesTheReturnedSystemResultWithoutReading() {
        settle = { tasks.stop("stop during settle") }
        val result = openApp()
        assertTrue(result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertTrue(result.text.contains("launched"))
        assertNull(result.image)
        assertEquals(1, launches)
        assertEquals(0, main.reads)
        assertEquals("stopped", core.log.last()!!.decision)
        assertTrue(core.log.last()!!.summary.contains("系统返回成功"))
        assertFalse(core.log.last()!!.summary.contains("launched"))
    }

    @Test fun interruptedSettlePreservesTheReturnedSystemResult() {
        settle = { throw InterruptedException("settle interrupted") }
        val result = openApp()
        assertTrue(result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertTrue(result.text.contains("launched"))
        assertEquals(1, launches)
        assertEquals(0, main.reads)
        assertEquals("stopped", core.log.last()!!.decision)
        assertTrue(core.log.last()!!.summary.contains("系统返回成功"))
    }

    @Test fun aFailedSystemReturnIsPreservedWhenItsOwnerWasReplaced() {
        launchResult = SysResult(false, "platform rejected private payload")
        afterStart = { replace(); tasks.setApp("successor") }
        val result = openApp()
        assertTrue(result.isError)
        assertTrue(result.text.startsWith(PhoneTasks.STOPPED_MESSAGE))
        assertTrue(result.text.contains(launchResult.message))
        assertEquals("successor", tasks.snapshot().app)
        assertEquals(0, main.reads)
        assertEquals(1, launches)
        assertEquals("stopped", core.log.last()!!.decision)
        assertTrue(core.log.last()!!.summary.contains("系统返回失败"))
        assertFalse(core.log.last()!!.summary.contains("private payload"))
    }

}
