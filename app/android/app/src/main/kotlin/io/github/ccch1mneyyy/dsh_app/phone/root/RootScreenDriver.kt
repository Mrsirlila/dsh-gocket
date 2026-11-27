package io.github.ccch1mneyyy.dsh_app.phone.root

import android.content.Context
import android.os.SystemClock
import android.util.Xml
import io.github.ccch1mneyyy.dsh_app.phone.Availability
import io.github.ccch1mneyyy.dsh_app.phone.CollectRejection
import io.github.ccch1mneyyy.dsh_app.phone.CollectResult
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAppIdentity
import io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import io.github.ccch1mneyyy.dsh_app.phone.ScreenSnapshot
import io.github.ccch1mneyyy.dsh_app.phone.TargetInfo
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.FormattedScreen
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenElement
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenFormat
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenInput
import io.github.ccch1mneyyy.dsh_app.phone.a11y.UiNode
import io.github.ccch1mneyyy.dsh_app.phone.a11y.UiWindow
import io.github.ccch1mneyyy.dsh_app.phone.a11y.WindowKind
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Drives the phone the way `adb shell` would: `su` runs uiautomator / input / am directly, so phone
 * control works without the accessibility service. Same [PhoneDriver] contract and the same text
 * rendering ([ScreenFormat]) as the accessibility driver; what differs is the evidence — every
 * element comes from the uiautomator dump taken for this observation, and there is no overlay, no
 * screenshot and no OCR here.
 *
 * Only the main screen: there is no virtual display without accessibility.
 */
internal class RootScreenDriver(private val context: Context) : PhoneDriver {
    override val screen: PhoneScreen = PhoneScreen.MAIN

    private var counter = 0
    @Volatile private var last: Cached? = null

    private class Cached(
        val id: String,
        val formatted: FormattedScreen,
        val packageName: String?,
        val appLabel: String?,
        val width: Int,
        val height: Int,
    ) {
        fun element(id: Int): ScreenElement? = formatted.elements.firstOrNull { it.id == id }
    }

    // ---------------------------------------------------------------- availability

    @Volatile private var suCheckedAt = 0L
    @Volatile private var suOk = false

    /** Cheap enough for the status snapshot: su is probed at most every [SU_RECHECK_MS]. */
    fun available(): Boolean = suAvailable()

    override fun availability(): Availability =
        if (suAvailable()) Availability(true)
        // l10n:keep agent stop reason
        else Availability(false, "rootUnavailable", "没有可用的 root：手机没有 su，或在 root 管理器里拒绝了 DSH。")

    private fun suAvailable(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - suCheckedAt < SU_RECHECK_MS) return suOk
        val ok = ROOT_PATHS.any { File(it).canExecute() } ||
            runCatching { su("echo ok", timeoutMs = 6_000)?.trim() == "ok" }.getOrDefault(false)
        suOk = ok
        suCheckedAt = now
        return ok
    }

    // ---------------------------------------------------------------- observe

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
        val parsed = dump()
        val id = nextId()
        if (parsed == null) {
            // l10n:keep agent screen result
            return ScreenSnapshot(id, "（读不到屏幕：uiautomator dump 没返回内容）", null, null, poorTree = true)
        }
        val metrics = context.resources.displayMetrics
        val width = parsed.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = parsed.height.takeIf { it > 0 } ?: metrics.heightPixels
        val previous = last
        val formatted = ScreenFormat.format(
            ScreenInput(
                id,
                appLabel = parsed.appLabel,
                packageName = parsed.packageName,
                screenWidth = width,
                screenHeight = height,
                keyboardShown = null,
                windows = listOf(
                    UiWindow(0, WindowKind.APP, parsed.packageName, null, Box(0, 0, width, height), parsed.root)
                ),
                detail = detail,
                previousKeys = previous?.takeIf { it.packageName == parsed.packageName }?.formatted?.keys,
            ),
        )
        last = Cached(id, formatted, parsed.packageName, parsed.appLabel, width, height)
        return ScreenSnapshot(
            id,
            formatted.text,
            parsed.appLabel,
            parsed.packageName,
            poorTree = formatted.poorTree,
            readPackages = setOfNotNull(parsed.packageName),
        )
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? {
        val cached = last?.takeIf { it.id == screenId } ?: return null
        val element = cached.element(elementId) ?: return null
        return TargetInfo(
            screenId,
            elementId,
            role = element.role,
            label = element.label,
            packageName = cached.packageName,
            appLabel = cached.appLabel,
            isPassword = element.password,
            isSecretField = element.secret,
            isEditable = element.editable,
            viewId = element.viewId,
        )
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult =
        // l10n:keep agent screen result
        CollectResult(emptyList(), 0, "root 模式只读一屏，不翻页；需要整页内容请用 a11y 读写。", last?.snapshot(), rejection = CollectRejection.UNAVAILABLE)

    // ---------------------------------------------------------------- actions

    override fun perform(action: PhoneAction): io.github.ccch1mneyyy.dsh_app.phone.ActionResult = when (action) {
        is PhoneAction.Tap -> tap(action.screenId, action.elementId, long = action.long)
        is PhoneAction.TapXY -> coordinate(action)
        is PhoneAction.Type -> type(action)
        is PhoneAction.Scroll -> scroll(action)
        is PhoneAction.Key -> key(action)
    }

    override fun open(target: PhoneAppIdentity): io.github.ccch1mneyyy.dsh_app.phone.ActionResult {
        val pkg = target.packageName
        val out = su("monkey -p $pkg -c android.intent.category.LAUNCHER 1", timeoutMs = 15_000)
        // l10n:keep agent result
        return if (out == null) result(false, "启动 $pkg 失败：root 命令没有返回。")
        else result(true, "已启动 $pkg。", observeAfter())
    }

    override fun foregroundPackage(): String? {
        val out = su("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'", timeoutMs = 10_000) ?: return null
        val match = FOCUS.find(out) ?: return null
        return match.groupValues[1]
    }

    private fun tap(screenId: String, elementId: Int, long: Boolean): io.github.ccch1mneyyy.dsh_app.phone.ActionResult {
        val element = last?.takeIf { it.id == screenId }?.element(elementId)
            // l10n:keep agent result
            ?: return result(false, "这个元素已经过期，请重新看一屏。", stale = true)
        val x = element.bounds.centerX
        val y = element.bounds.centerY
        val out = if (long) su("input swipe $x $y $x $y 600", timeoutMs = 15_000) else su("input tap $x $y", timeoutMs = 15_000)
        return if (out == null) result(false, "点击没有执行：root 命令没有返回。")
        // l10n:keep agent result
        else result(true, "已点击「${element.label}」。", observeAfter())
    }

    private fun coordinate(action: PhoneAction.TapXY): io.github.ccch1mneyyy.dsh_app.phone.ActionResult {
        val out = when (action.gesture) {
            PhoneAction.CoordinateGesture.TAP -> su("input tap ${action.x} ${action.y}", timeoutMs = 15_000)
            PhoneAction.CoordinateGesture.LONG ->
                su("input swipe ${action.x} ${action.y} ${action.x} ${action.y} ${action.durationMs}", timeoutMs = 15_000)
            PhoneAction.CoordinateGesture.SWIPE -> su(
                "input swipe ${action.x} ${action.y} ${action.x2 ?: action.x} ${action.y2 ?: action.y} ${action.durationMs}",
                timeoutMs = 15_000,
            )
        }
        // l10n:keep agent result
        return if (out == null) result(false, "手势没有执行：root 命令没有返回。") else result(true, "已执行手势。", observeAfter())
    }

    private fun type(action: PhoneAction.Type): io.github.ccch1mneyyy.dsh_app.phone.ActionResult {
        val element = last?.takeIf { it.id == action.screenId }?.element(action.elementId)
        if (element != null && element.editable) {
            su("input tap ${element.bounds.centerX} ${element.bounds.centerY}", timeoutMs = 15_000)
            SystemClock.sleep(FOCUS_SETTLE_MS)
        }
        val quoted = action.text.replace("\\", "\\\\").replace(" ", "%s").replace("'", "\\'")
        val out = su("input text '$quoted'", timeoutMs = 20_000)
            ?: // l10n:keep agent result
            return result(false, "输入没有执行：root 命令没有返回。")
        if (action.submit) su("input keyevent 66", timeoutMs = 10_000)
        // l10n:keep agent result
        return result(true, if (action.submit) "已输入并提交。" else "已输入。", observeAfter())
    }

    private fun scroll(action: PhoneAction.Scroll): io.github.ccch1mneyyy.dsh_app.phone.ActionResult {
        val box = last?.takeIf { it.id == action.screenId }
            ?.let { cached -> action.elementId?.let { cached.element(it)?.bounds } }
            ?: last?.takeIf { it.id == action.screenId }?.let { Box(0, 0, it.width, it.height) }
            // l10n:keep agent result
            ?: return result(false, "这个元素已经过期，请重新看一屏。", stale = true)
        val (fx, fy, tx, ty) = when (action.direction) {
            PhoneAction.Direction.UP -> listOf(0.5, 0.75, 0.5, 0.25)
            PhoneAction.Direction.DOWN -> listOf(0.5, 0.25, 0.5, 0.75)
            PhoneAction.Direction.LEFT -> listOf(0.8, 0.5, 0.2, 0.5)
            PhoneAction.Direction.RIGHT -> listOf(0.2, 0.5, 0.8, 0.5)
        }
        fun at(share: Double, start: Int, size: Int) = (start + size * share).toInt()
        val out = su(
            "input swipe ${at(fx, box.left, box.width)} ${at(fy, box.top, box.height)} " +
                "${at(tx, box.left, box.width)} ${at(ty, box.top, box.height)} 300",
            timeoutMs = 15_000,
        )
        // l10n:keep agent result
        return if (out == null) result(false, "滑动没有执行：root 命令没有返回。") else result(true, "已滑动。", observeAfter())
    }

    private fun key(action: PhoneAction.Key): io.github.ccch1mneyyy.dsh_app.phone.ActionResult {
        val code = when (action.key) {
            PhoneAction.GlobalKey.BACK -> 4
            PhoneAction.GlobalKey.HOME -> 3
            PhoneAction.GlobalKey.RECENTS -> 187
            PhoneAction.GlobalKey.NOTIFICATIONS -> 83
        }
        val out = su("input keyevent $code", timeoutMs = 15_000)
        // l10n:keep agent result
        return if (out == null) result(false, "按键没有执行：root 命令没有返回。") else result(true, "已按键。", observeAfter())
    }

    // ---------------------------------------------------------------- plumbing

    private fun observeAfter(): ScreenSnapshot? = runCatching { observe(detail = false, image = false) }.getOrNull()

    private fun Cached.snapshot(): ScreenSnapshot = ScreenSnapshot(
        id,
        formatted.text,
        appLabel,
        packageName,
        poorTree = formatted.poorTree,
        readPackages = setOfNotNull(packageName),
    )

    private fun result(ok: Boolean, message: String, screen: ScreenSnapshot? = null, stale: Boolean = false) =
        io.github.ccch1mneyyy.dsh_app.phone.ActionResult(ok, message, screen, staleSnapshot = stale)

    private fun nextId(): String = "${PhoneScreen.MAIN.prefix}${++counter}"

    /** One `su` round trip; null when the command could not run or returned nothing. */
    private fun su(command: String, timeoutMs: Long = 12_000): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy()
            return@runCatching null
        }
        out.ifBlank { null }
    }.getOrNull()

    private class Parsed(
        val root: UiNode?,
        val packageName: String?,
        val appLabel: String?,
        val width: Int,
        val height: Int,
    )

    /** Takes a fresh uiautomator dump as root and turns it into a [UiNode] tree. */
    private fun dump(): Parsed? {
        val raw = su("uiautomator dump --compressed $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH", timeoutMs = 25_000) ?: return null
        val start = raw.indexOf("<?xml")
        val xml = if (start >= 0) raw.substring(start) else raw
        if (!xml.contains("<hierarchy")) return null
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var packageName: String? = null
        var root: UiNode? = null
        var displayWidth = 0
        var displayHeight = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val node = readNode(parser)
                if (root == null) {
                    root = node
                    packageName = parser.getAttributeValue(null, "package")
                    displayWidth = node.bounds.right
                    displayHeight = node.bounds.bottom
                }
            }
            event = parser.next()
        }
        if (root == null) return null
        val pkg = packageName
        return Parsed(root, pkg, labelOf(pkg), displayWidth, displayHeight)
    }

    private fun readNode(parser: XmlPullParser): UiNode {
        val depth = parser.depth
        val className = parser.getAttributeValue(null, "class") ?: ""
        val viewId = parser.getAttributeValue(null, "resource-id")?.takeIf { it.isNotBlank() }
        val text = parser.getAttributeValue(null, "text")?.takeIf { it.isNotBlank() }
        val desc = parser.getAttributeValue(null, "content-desc")?.takeIf { it.isNotBlank() }
        val hint = parser.getAttributeValue(null, "hint")?.takeIf { it.isNotBlank() }
        val bounds = parseBounds(parser.getAttributeValue(null, "bounds"))
        val clickable = parser.getAttributeValue(null, "clickable") == "true"
        val longClickable = parser.getAttributeValue(null, "long-clickable") == "true"
        val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
        val checkable = parser.getAttributeValue(null, "checkable") == "true"
        val checked = parser.getAttributeValue(null, "checked") == "true"
        val selected = parser.getAttributeValue(null, "selected") == "true"
        val focused = parser.getAttributeValue(null, "focused") == "true"
        val enabled = parser.getAttributeValue(null, "enabled") != "false"
        val password = parser.getAttributeValue(null, "password") == "true"
        val children = ArrayList<UiNode>()
        var event = parser.next()
        while (event != XmlPullParser.END_TAG || parser.depth > depth) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") children += readNode(parser)
            event = parser.next()
        }
        return UiNode(
            className = className,
            text = text,
            desc = desc,
            hint = hint,
            viewId = viewId,
            bounds = bounds,
            clickable = clickable,
            longClickable = longClickable,
            editable = className.contains("EditText"),
            scrollable = scrollable,
            checkable = checkable,
            checked = checked,
            selected = selected,
            focused = focused,
            enabled = enabled,
            password = password,
            children = children,
            rawChildCount = children.size,
        )
    }

    private fun parseBounds(raw: String?): Box {
        val match = raw?.let { BOUNDS.find(it) } ?: return Box(0, 0, 0, 0)
        val (l, t, r, b) = match.destructured
        return Box(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private fun labelOf(packageName: String?): String? {
        val pkg = packageName ?: return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private companion object {
        const val SU_RECHECK_MS = 15_000L
        const val FOCUS_SETTLE_MS = 250L
        const val DUMP_PATH = "/data/local/tmp/dsh-root-screen.xml"
        val ROOT_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )
        val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        val FOCUS = Regex("""(?:mCurrentFocus|mFocusedApp)[^}]*?\s([\w.]+)/""")
    }
}
