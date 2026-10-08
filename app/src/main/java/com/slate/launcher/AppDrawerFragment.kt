package com.slate.launcher

import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.shortcuts.PinnedShortcut
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination
import kotlin.math.abs

class AppDrawerFragment : Fragment() {

    private lateinit var appList: HomeRecyclerView
    private lateinit var listLayoutManager: CenteredLinearLayoutManager
    private val homeAdapter = HomeAdapter()
    private lateinit var fastScroll: AlphaFastScroll
    private lateinit var fastScrollBubble: TextView
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: AppRepository
    private var touchStartedOnApp = false
    private var scrollOffsetOnDown = 0
    private var statusBarHeight = 0
    private var bottomInset = 0
    /**
     * Reference to the currently-showing FAQ detail dialog (if any) so the fragment can dismiss
     * it in onDestroyView and avoid a WindowLeaked exception when the activity is recreated
     * (e.g. on configuration change) while the dialog is open. Mirrors the pattern used by
     * [PrivacyPolicyDialog.activeDialog].
     */
    private var activeFaqDetailDialog: Dialog? = null
    private var reconcileDoubleTapTask: Runnable? = null
    private lateinit var singleFingerDetector: GestureDetector

    /** Null = main view; non-null = home is showing the contents of that folder. */
    private var currentFolderId: String? = null
    private var mainListAnchor: ScrollAnchor? = null
    private var stoppedListAnchor: ScrollAnchor? = null
    private val selectedKeys = LinkedHashSet<String>()
    private var viewAppKeys: Set<String> = emptySet()
    private val isSelecting: Boolean get() = selectedKeys.isNotEmpty()
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private var activeConfirmDialog: Dialog? = null
    private val uninstallQueue = ArrayDeque<String>()
    private var uninstalling: String? = null
    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val closed = uninstalling
        uninstalling = null
        if (closed != null && !isInstalled(closed)) launchNextUninstall()
        else uninstallQueue.clear()
    }

    private data class ScrollAnchor(val position: Int, val offset: Int)

    private data class RenderState(
        val items: List<HomeItem>,
        val fontFamily: String,
        val fontWeight: Int,
        val fontSize: Int,
        val lineSpacing: Int,
        val wordSpacing: Int,
        val alignment: String,
        val textColor: String,
        val folderTextColor: String?,
        val folderStyle: String,
        val appColors: Map<String, String>,
        val selectedKeys: Set<String>
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_app_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager(requireContext())
        repository = AppRepository(requireContext(), prefs) {
            if (isResumed && view != null) buildAppList()
        }
        appList = view.findViewById(R.id.appList)
        listLayoutManager = CenteredLinearLayoutManager(requireContext())
        appList.layoutManager = listLayoutManager
        appList.itemAnimator = null
        appList.setItemViewCacheSize(0)
        appList.adapter = homeAdapter
        selectionBar = view.findViewById(R.id.selectionBar)
        selectionCount = view.findViewById(R.id.selectionCount)
        view.findViewById<View>(R.id.selectionActions).setOnClickListener { showSelectionMenu() }
        view.findViewById<View>(R.id.selectionCancel).setOnClickListener { clearSelection() }
        selectionBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyChromeLayout() }
        fastScroll = view.findViewById(R.id.fastScroll)
        fastScrollBubble = view.findViewById(R.id.fastScrollBubble)
        setupFastScroll()

        singleFingerDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    scrollOffsetOnDown = appList.computeVerticalScrollOffset()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isSelecting) return
                    if (!touchStartedOnApp) {
                        AuthGate.authenticatePinOnly(
                            activity = requireActivity(),
                            prefs = prefs,
                            pinManager = PinManager(prefs),
                            enabled = prefs.lockLongPressMenusEnabled,
                            title = getString(R.string.code_home_menu),
                            onSuccess = { showHomeLongPressDialog() }
                        )
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isSelecting) return false
                    if (!prefs.doubleTapToLock || touchStartedOnApp) return false
                    lockScreen()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    if (isSelecting) return false
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    if (absDx < 120f && absDy < 120f) return false
                    if (abs(velocityX) < 500f && abs(velocityY) < 500f) return false
                    if ((if (absDx > absDy) absDy / absDx else absDx / absDy) > 0.65f) return false
                    if (abs(appList.computeVerticalScrollOffset() - scrollOffsetOnDown) >
                        resources.displayMetrics.density * 80) return false
                    val dir = if (absDx > absDy) {
                        if (dx > 0) Direction.RIGHT else Direction.LEFT
                    } else {
                        if (dy > 0) Direction.DOWN else Direction.UP
                    }
                    if (dir == Direction.DOWN && appList.canScrollVertically(-1)) return false
                    return executeGestureAction(1, dir)
                }
            }
        )
        appList.onTouchObserved = { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchStartedOnApp = isTouchOnLabel(event)
            }
            singleFingerDetector.onTouchEvent(event)
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSelecting) clearSelection()
                    else if (currentFolderId != null) exitFolder()
                }
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            if (top != statusBarHeight || bottom != bottomInset) {
                statusBarHeight = top
                bottomInset = bottom
                applyChromeLayout()
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    override fun onResume() {
        super.onResume()
        val bg = parseColorSafe(prefs.backgroundColor)
        appList.setBackgroundColor(bg)
        requireView().setBackgroundColor(bg)
        val restoreMainScroll = currentFolderId != null
        currentFolderId = null
        selectedKeys.clear()
        if (appList.adapter == null) appList.adapter = homeAdapter
        applyChromeLayout()
        buildAppList()
        if (restoreMainScroll) restoreScrollAnchor(mainListAnchor)
        else restoreScrollAnchor(stoppedListAnchor)
        stoppedListAnchor = null
        reconcileDoubleTapPref()
        PinnedShortcutStore.performHealthCheckIfDue(requireContext(), prefs) {
            if (isResumed && view != null) buildAppList()
        }
    }

    private fun reconcileDoubleTapPref() {
        if (!prefs.doubleTapToLock || SlateAccessibilityService.isEnabled(requireContext())) return
        reconcileDoubleTapTask?.let { view?.removeCallbacks(it) }
        val task = Runnable {
            reconcileDoubleTapTask = null
            if (isAdded && prefs.doubleTapToLock &&
                !SlateAccessibilityService.isEnabled(requireContext())
            ) prefs.doubleTapToLock = false
        }
        reconcileDoubleTapTask = task
        view?.postDelayed(task, 500)
    }

    override fun onPause() {
        super.onPause()
        fastScrollBubble.animate().cancel()
    }

    override fun onStop() {
        appList.stopScroll()
        stoppedListAnchor = if (currentFolderId == null) captureScrollAnchor() else mainListAnchor
        reconcileDoubleTapTask?.let { view?.removeCallbacks(it) }
        reconcileDoubleTapTask = null
        appList.adapter = null
        homeAdapter.clear()
        appList.recycledViewPool.clear()
        super.onStop()
    }

    override fun onDestroyView() {
        reconcileDoubleTapTask?.let { view?.removeCallbacks(it) }
        reconcileDoubleTapTask = null
        appList.onTouchObserved = null
        appList.adapter = null
        homeAdapter.clear()
        appList.recycledViewPool.clear()
        repository.close()
        selectedKeys.clear()
        viewAppKeys = emptySet()
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        uninstallQueue.clear()
        uninstalling = null
        activeFaqDetailDialog?.let { runCatching { it.dismiss() } }
        activeFaqDetailDialog = null
        PinEntryDialog.dismissActive()
        super.onDestroyView()
    }

    private fun applyChromeLayout() {
        val verticalPadding = (40 * resources.displayMetrics.density).toInt()
        val selectionHeight = if (selectionBar.visibility == View.VISIBLE) selectionBar.height else 0
        appList.setPadding(
            appList.paddingLeft, verticalPadding + statusBarHeight,
            appList.paddingRight, verticalPadding + maxOf(bottomInset, selectionHeight)
        )
        if (selectionBar.paddingBottom != bottomInset) {
            selectionBar.setPadding(selectionBar.paddingLeft, 0, selectionBar.paddingRight, bottomInset)
        }
    }

    private fun isTouchOnLabel(event: MotionEvent): Boolean {
        val row = appList.findChildViewUnder(event.x, event.y) as? ViewGroup ?: return false
        val label = row.getChildAt(0) ?: return false
        val x = event.x - row.left
        val y = event.y - row.top
        return x >= label.left && x < label.right && y >= label.top && y < label.bottom
    }

    private fun captureScrollAnchor(): ScrollAnchor? {
        val position = listLayoutManager.findFirstVisibleItemPosition()
        if (position < 0) return null
        val row = listLayoutManager.findViewByPosition(position) ?: return null
        return ScrollAnchor(position, row.top - appList.paddingTop)
    }

    private fun restoreScrollAnchor(anchor: ScrollAnchor?) {
        if (anchor != null) {
            listLayoutManager.scrollToPositionWithOffset(anchor.position, anchor.offset)
        }
    }

    // ── Gesture execution ─────────────────────────────────────────

    private fun executeGestureAction(
        fingers: Int,
        dir: Direction
    ): Boolean {
        return when (val action = prefs.getGestureAction(fingers, dir)) {
            is GestureAction.None              -> false
            is GestureAction.OpenNotifications -> { expandNotificationsPanel(); true }
            is GestureAction.LockScreen        -> { lockScreen(); true }
            is GestureAction.OpenSettings      -> {
                AuthGate.authenticatePinOnly(
                    activity = requireActivity(),
                    prefs = prefs,
                    pinManager = PinManager(prefs),
                    enabled = prefs.lockLongPressMenusEnabled,
                    title = getString(R.string.settings_title),
                    onSuccess = {
                        startActivity(Intent(requireContext(), SettingsActivity::class.java))
                    }
                )
                true
            }
            is GestureAction.ToggleWifi        -> { toggleWifi(); true }
            is GestureAction.ToggleBluetooth   -> { toggleBluetooth(); true }
            is GestureAction.ToggleLocation    -> {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }
            is GestureAction.OpenCamera        -> {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(intent); true } catch (_: Exception) { false }
            }
            is GestureAction.OpenApp           -> {
                if (AppKey.serialOf(action.key) != null) false
                else {
                    val intent = requireContext().packageManager
                        .getLaunchIntentForPackage(action.key)
                    if (intent == null) false else runCatching { startActivity(intent) }.isSuccess
                }
            }
        }
    }

    // ── App list ──────────────────────────────────────────────────

    private fun buildAppList() {
        val snapshot = repository.getHomeSnapshot(currentFolderId)
        if (currentFolderId != null && snapshot.items.firstOrNull() != HomeItem.BackOut) {
            currentFolderId = null
        }
        viewAppKeys = snapshot.items.filterIsInstance<HomeItem.AppItem>()
            .mapTo(LinkedHashSet()) { it.info.key }
        selectedKeys.retainAll(viewAppKeys)
        renderItems(snapshot.items)
        syncSelectionBar()
        if (currentFolderId == null) configureFastScroll(snapshot.allApps)
        else fastScroll.visibility = View.GONE
    }

    private fun renderItems(items: List<HomeItem>) {
        homeAdapter.submit(RenderState(
            items, prefs.fontFamily, prefs.fontWeight, prefs.maxFontSize,
            prefs.lineSpacing, prefs.wordSpacing, prefs.textAlignment,
            prefs.appTextColor, prefs.folderTextColor, prefs.folderStyle,
            prefs.getAllAppColors(), selectedKeys.toSet()
        ))
    }

    private inner class HomeAdapter : RecyclerView.Adapter<HomeAdapter.RowHolder>() {
        private var state: RenderState? = null
        private var rowTypeface = Typeface.DEFAULT
        private var defaultColor = Color.GRAY
        private var horizontalPadding = 0
        private var verticalPadding = 0
        private var alignment = Gravity.CENTER_HORIZONTAL

        fun submit(next: RenderState) {
            if (next == state) return
            state = next
            rowTypeface = buildTypeface()
            defaultColor = parseColorSafe(next.textColor, Color.GRAY)
            val density = resources.displayMetrics.density
            horizontalPadding = (next.wordSpacing * density).toInt()
            verticalPadding = (next.lineSpacing * density).toInt()
            alignment = when (next.alignment) {
                "left" -> Gravity.START
                "right" -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            notifyDataSetChanged()
        }

        fun clear() {
            state = null
            rowTypeface = Typeface.DEFAULT
        }

        fun firstPositionForLetter(letter: Char): Int =
            state?.items?.indexOfFirst { rowLabel(it).firstOrNull()?.uppercaseChar() == letter } ?: -1

        override fun getItemCount(): Int = state?.items?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val label = SelectionTextView(parent.context)
            row.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return RowHolder(row, label)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val currentState = state ?: return
            val item = currentState.items[position]
            holder.item = item
            holder.label.apply {
                text = rowLabel(item)
                textSize = currentState.fontSize.toFloat()
                setTextColor(when (item) {
                    is HomeItem.AppItem -> colorForApp(item.info, defaultColor)
                    is HomeItem.FolderItem -> colorForFolder(item.folder, defaultColor)
                    else -> defaultColor
                })
                alpha = when (item) {
                    is HomeItem.ShortcutItem -> if (PinnedShortcutStore.isLikelyStale(item.shortcut)) 0.5f else 1f
                    HomeItem.BackOut -> 0.7f
                    else -> 1f
                }
                typeface = rowTypeface
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                val selecting = currentState.selectedKeys.isNotEmpty()
                val selected = item is HomeItem.AppItem && item.info.key in currentState.selectedKeys
                setSelectionMarker(selected, selecting && item is HomeItem.AppItem)
                isSelected = selected
                contentDescription = if (selecting && item is HomeItem.AppItem) {
                    getString(
                        if (selected) R.string.selection_row_selected else R.string.selection_row_not_selected,
                        item.info.name
                    )
                } else null
                isLongClickable = item != HomeItem.BackOut && (!selecting || item is HomeItem.AppItem)
                layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                    gravity = alignment
                }
            }
        }

        override fun onViewRecycled(holder: RowHolder) {
            holder.item = null
            super.onViewRecycled(holder)
        }

        inner class RowHolder(row: FrameLayout, val label: SelectionTextView) : RecyclerView.ViewHolder(row) {
            var item: HomeItem? = null

            init {
                ViewCompat.setAccessibilityDelegate(label, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (isSelecting && item is HomeItem.AppItem) {
                            info.className = "android.widget.CheckBox"
                            info.isCheckable = true
                            info.isChecked = (item as HomeItem.AppItem).info.key in selectedKeys
                        }
                    }
                })
                label.setOnClickListener { item?.let { onRowClicked(it) } }
                label.setOnLongClickListener {
                    val current = item ?: return@setOnLongClickListener false
                    onRowLongClicked(current)
                }
            }
        }
    }

    private fun rowLabel(item: HomeItem): String = when (item) {
        is HomeItem.AppItem -> item.info.name
        is HomeItem.FolderItem -> folderLabel(item.folder, item.visibleCount)
        is HomeItem.ShortcutItem -> getString(R.string.shortcut_row_label, item.shortcut.pinnedLabel)
        HomeItem.BackOut -> getString(R.string.folder_back)
    }

    private fun onRowClicked(item: HomeItem) {
        if (isSelecting) {
            if (item is HomeItem.AppItem) {
                if (!selectedKeys.add(item.info.key)) selectedKeys.remove(item.info.key)
                buildAppList()
            }
            return
        }
        when (item) {
            is HomeItem.AppItem -> launchApp(item.info)
            is HomeItem.FolderItem -> enterFolder(item.folder.id)
            is HomeItem.ShortcutItem -> launchShortcut(item.shortcut)
            HomeItem.BackOut -> exitFolder()
        }
    }

    private fun onRowLongClicked(item: HomeItem): Boolean {
        if (isSelecting) {
            if (item is HomeItem.AppItem) showSelectionMenu()
            return true
        }
        val title = when (item) {
            is HomeItem.AppItem -> R.string.code_app_menu
            is HomeItem.FolderItem -> R.string.code_folder_menu
            is HomeItem.ShortcutItem -> R.string.code_shortcut_menu
            HomeItem.BackOut -> return false
        }
        AuthGate.authenticatePinOnly(
            activity = requireActivity(),
            prefs = prefs,
            pinManager = PinManager(prefs),
            enabled = prefs.lockLongPressMenusEnabled,
            title = getString(title),
            onSuccess = {
                when (item) {
                    is HomeItem.AppItem -> showAppMenu(item.info)
                    is HomeItem.FolderItem -> showFolderMenu(item.folder)
                    is HomeItem.ShortcutItem -> showShortcutMenu(item.shortcut)
                    HomeItem.BackOut -> Unit
                }
            }
        )
        return true
    }

    /** Compose the visible folder marker, including its count when selected. */
    private fun folderLabel(folder: Folder, visibleCount: Int): String =
        when (prefs.folderStyle) {
            PreferencesManager.FOLDER_STYLE_SLASH    -> "${folder.name}/"
            PreferencesManager.FOLDER_STYLE_BULLET   -> "• ${folder.name}"
            PreferencesManager.FOLDER_STYLE_BRACKETS -> "[${folder.name}]"
            PreferencesManager.FOLDER_STYLE_COUNT    -> "${folder.name} ($visibleCount)"
            PreferencesManager.FOLDER_STYLE_PLAIN    -> folder.name
            else                                     -> "${folder.name} ›"
        }

    private fun enterFolder(folderId: String) {
        mainListAnchor = captureScrollAnchor()
        currentFolderId = folderId
        buildAppList()
        listLayoutManager.scrollToPositionWithOffset(0, 0)
    }

    private fun exitFolder() {
        currentFolderId = null
        buildAppList()
        restoreScrollAnchor(mainListAnchor)
    }

    private fun colorForApp(app: AppInfo, defaultTextColor: Int): Int =
        prefs.getAppTextColor(app.key)?.let { parseColorSafe(it, defaultTextColor) }
            ?: defaultTextColor

    private fun colorForFolder(folder: Folder, defaultTextColor: Int): Int =
        (folder.color ?: prefs.folderTextColor)?.let { parseColorSafe(it, defaultTextColor) } ?: defaultTextColor

    private fun launcherApps() = PinnedShortcutStore.launcherApps(requireContext())

    private fun launchShortcut(shortcut: PinnedShortcut) {
        val ok = PinnedShortcutStore.startShortcut(launcherApps(), shortcut)
        if (!ok) {
            Toast.makeText(requireContext(), getString(R.string.code_this_shortcut_is_no_longer_available), Toast.LENGTH_SHORT).show()
            PinnedShortcutStore.refreshOne(prefs, launcherApps(), shortcut)
            buildAppList()
        }
    }

    private enum class ShortcutMenuAction(val label: Int) {
        REMOVE(R.string.code_remove),
        REFRESH(R.string.code_refresh),
        OPEN_APP(R.string.open_app_label),
    }

    private fun showShortcutMenu(shortcut: PinnedShortcut) {
        val sourceLabel = appLabelFor(shortcut.sourcePackage) ?: shortcut.sourcePackage
        val actions = ShortcutMenuAction.entries
        SlateListDialog(
            context = requireContext(),
            title = shortcut.pinnedLabel,
            items = actions.map {
                if (it == ShortcutMenuAction.OPEN_APP) getString(it.label, sourceLabel)
                else getString(it.label)
            },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (actions[index]) {
                ShortcutMenuAction.REMOVE -> {
                    // This row only ever renders the APP_LIST destination - unpin just that one,
                    // leaving an independent widget-strip pin (if any) untouched.
                    PinnedShortcutStore.remove(
                        prefs, launcherApps(), shortcut.sourcePackage, shortcut.shortcutId,
                        ShortcutDestination.APP_LIST
                    )
                    buildAppList()
                }
                ShortcutMenuAction.REFRESH -> {
                    PinnedShortcutStore.refreshOne(prefs, launcherApps(), shortcut)
                    buildAppList()
                }
                ShortcutMenuAction.OPEN_APP -> {
                    // The remaining item is always "Open $sourceLabel".
                    val intent = requireContext().packageManager
                        .getLaunchIntentForPackage(shortcut.sourcePackage)
                    if (intent != null) {
                        runCatching { startActivity(intent) }
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.code_app_not_installed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.show()
    }

    private fun appLabelFor(key: String): String? = runCatching {
        if (AppKey.serialOf(key) != null) return@runCatching null
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString()
    }.getOrNull()

    // Fast scroll only operates over an alphabetical list, so it's mutually exclusive with
    // Sort by usage. We preserve `prefs.alphabeticalFastScroll` even when Sort by usage is on
    // (so the toggle re-lights at the user's previous position when they switch sort modes),
    // but this getter is the single source of truth for the render path - returning false here
    // suppresses both the fast-scroll widget and the forced-alphabetical override in
    // AppRepository, so Sort by usage takes effect immediately when the user enables it.
    private fun useFastScroll(): Boolean =
        prefs.alphabeticalFastScroll && !prefs.sortByUsage

    /**
     * Resolve the apps-list typeface. `fontFamily` defaults to a non-empty Google Font key, so
     * [Typography.buildTypeface] never returns null here - the `?: Typeface.DEFAULT` fallback
     * is defensive only (would only trip if a future code path wrote both sentinels to the apps'
     * pref).
     */
    private fun buildTypeface(): Typeface =
        Typography.buildTypeface(requireContext(), prefs.fontFamily, prefs.fontWeight)
            ?: Typeface.DEFAULT

    // ── Fast scroll ───────────────────────────────────────────────

    private fun setupFastScroll() {
        fastScroll.onLetterTouched = { letter ->
            fastScrollBubble.text = letter.toString()
            scrollToLetter(letter)
        }
        fastScroll.onTouchStateChanged = { active ->
            if (active) {
                fastScrollBubble.animate().cancel()
                fastScrollBubble.alpha = 1f
                fastScrollBubble.visibility = View.VISIBLE
            } else {
                fastScrollBubble.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { fastScrollBubble.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun configureFastScroll(apps: List<AppInfo>) {
        if (!useFastScroll()) {
            fastScroll.visibility = View.GONE
            return
        }
        val letters = apps
            .mapNotNull { it.name.firstOrNull()?.uppercaseChar() }
            .filter { it in 'A'..'Z' }
            .distinct()
            .sorted()
        if (letters.size < 3) {
            fastScroll.visibility = View.GONE
            return
        }
        val color = parseColorSafe(prefs.appTextColor, Color.GRAY)
        fastScroll.textColor = color
        fastScrollBubble.setTextColor(color)
        fastScroll.setLetters(letters)
        fastScroll.visibility = View.VISIBLE
    }

    /** Move to the first visible row whose label starts with [letter]. */
    private fun scrollToLetter(letter: Char) {
        val position = homeAdapter.firstPositionForLetter(letter)
        if (position < 0) return
        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        }
        scroller.targetPosition = position
        listLayoutManager.startSmoothScroll(scroller)
    }

    private fun launchApp(app: AppInfo) {
        prefs.incrementUsage(app.key)
        val intent = requireContext().packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null || runCatching { startActivity(intent) }.isFailure) {
            Toast.makeText(requireContext(), getString(R.string.code_app_not_installed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSelection() {
        if (!isSelecting) return
        selectedKeys.clear()
        buildAppList()
    }

    private fun syncSelectionBar() {
        selectionBar.visibility = if (isSelecting) View.VISIBLE else View.GONE
        if (isSelecting) {
            selectionCount.text = resources.getQuantityString(
                R.plurals.selection_count, selectedKeys.size, selectedKeys.size
            )
            val bg = parseColorSafe(prefs.backgroundColor)
            val primary = if (isColorLight(bg)) Color.BLACK else Color.WHITE
            selectionBar.setBackgroundColor(bg)
            selectionCount.setTextColor(primary)
            selectionBar.findViewById<TextView>(R.id.selectionActions).setTextColor(primary)
            selectionBar.findViewById<TextView>(R.id.selectionCancel).setTextColor(primary)
        }
        applyChromeLayout()
    }

    private enum class SelectionMenuAction(val label: Int) {
        PIN(R.string.code_pin_to_top),
        UNPIN(R.string.code_unpin),
        MOVE_TO_FOLDER(R.string.code_move_to_folder),
        REMOVE_FROM_FOLDER(R.string.code_remove_from_folder),
        CUSTOM_COLOR(R.string.code_custom_color),
        HIDE(R.string.code_hide),
        UNINSTALL(R.string.code_uninstall),
        SELECT_ALL(R.string.menu_select_all),
    }

    private fun showSelectionMenu() {
        val apps = repository.getAllApps().filter { it.key in selectedKeys }.distinctBy { it.key }
        if (apps.isEmpty()) {
            clearSelection()
            return
        }
        val keys = apps.map { it.key }
        val inFolders = FolderStore.keysInAnyFolder(prefs)
        val removable = apps.filterNot { isSystemApp(it.packageName) }.map { it.packageName }
        val actions = buildList {
            add(if (keys.all { prefs.isPinned(it) }) SelectionMenuAction.UNPIN else SelectionMenuAction.PIN)
            add(SelectionMenuAction.MOVE_TO_FOLDER)
            if (keys.any { it in inFolders }) add(SelectionMenuAction.REMOVE_FROM_FOLDER)
            add(SelectionMenuAction.CUSTOM_COLOR)
            add(SelectionMenuAction.HIDE)
            if (removable.isNotEmpty()) add(SelectionMenuAction.UNINSTALL)
            if (viewAppKeys.any { it !in selectedKeys }) add(SelectionMenuAction.SELECT_ALL)
        }
        SlateListDialog(
            context = requireContext(),
            title = resources.getQuantityString(R.plurals.selection_count, keys.size, keys.size),
            items = actions.map { getString(it.label) },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (actions[index]) {
                SelectionMenuAction.PIN -> {
                    FolderStore.removeAppsFromFolders(prefs, keys)
                    prefs.pinApps(keys)
                    finishSelection()
                }
                SelectionMenuAction.UNPIN -> { prefs.unpinApps(keys); finishSelection() }
                SelectionMenuAction.MOVE_TO_FOLDER -> showFolderPicker { id ->
                    FolderStore.addAppsToFolder(prefs, id, keys)
                    finishSelection()
                }
                SelectionMenuAction.REMOVE_FROM_FOLDER -> {
                    FolderStore.removeAppsFromFolders(prefs, keys)
                    finishSelection()
                }
                SelectionMenuAction.CUSTOM_COLOR -> showSelectionColorPicker(keys)
                SelectionMenuAction.HIDE -> showConfirmDialog(
                    getString(R.string.selection_hide_title),
                    resources.getQuantityString(R.plurals.selection_hide_body, keys.size, keys.size),
                    getString(R.string.code_hide)
                ) { hideSelection(apps) }
                SelectionMenuAction.UNINSTALL -> confirmUninstallSelection(removable, apps.size - removable.size)
                SelectionMenuAction.SELECT_ALL -> { selectedKeys.addAll(viewAppKeys); buildAppList() }
            }
        }.show()
    }

    private fun showSelectionColorPicker(keys: List<String>) {
        val custom = keys.mapNotNull { prefs.getAppTextColor(it) }
        ColorPickerDialog(
            context = requireContext(),
            title = resources.getQuantityString(R.plurals.selection_apps, keys.size, keys.size),
            initialColor = custom.distinct().singleOrNull() ?: prefs.appTextColor,
            bgColor = prefs.backgroundColor,
            showReset = custom.isNotEmpty(),
            onReset = { prefs.clearAppTextColors(keys); finishSelection() }
        ) { hex ->
            prefs.setAppTextColors(keys, hex)
            finishSelection()
        }.show()
    }

    private fun finishSelection() {
        selectedKeys.clear()
        finishFolderChange()
    }

    private fun hideSelection(apps: List<AppInfo>) {
        prefs.hideApps(apps.map { it.key })
        val removed = apps.sumOf {
            PinnedShortcutStore.removeForPackage(prefs, launcherApps(), it.packageName).size
        }
        finishSelection()
        if (removed > 0) {
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(R.plurals.selection_shortcuts_removed, removed, removed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val info = requireContext().packageManager.getApplicationInfo(packageName, 0)
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(true)

    private fun isInstalled(packageName: String): Boolean = runCatching {
        requireContext().packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess

    private fun confirmUninstallSelection(packages: List<String>, skipped: Int) {
        val body = buildString {
            append(resources.getQuantityString(R.plurals.selection_uninstall_body, packages.size, packages.size))
            if (skipped > 0) {
                append(" ")
                append(resources.getQuantityString(R.plurals.selection_uninstall_skipped, skipped, skipped))
            }
        }
        showConfirmDialog(
            getString(R.string.selection_uninstall_title), body, getString(R.string.code_uninstall)
        ) {
            finishSelection()
            uninstallQueue.clear()
            uninstallQueue.addAll(packages)
            launchNextUninstall()
        }
    }

    private fun launchNextUninstall() {
        val next = uninstallQueue.removeFirstOrNull()
        uninstalling = next
        if (next == null) return
        val prompt = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", next, null))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        runCatching { uninstallLauncher.launch(prompt) }.onFailure {
            uninstalling = null
            uninstallQueue.clear()
            Toast.makeText(requireContext(), R.string.selection_uninstall_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private enum class AppMenuAction(val label: Int) {
        PIN(R.string.code_pin_to_top),
        UNPIN(R.string.code_unpin),
        APP_INFO(R.string.code_app_info),
        HIDE(R.string.code_hide),
        UNINSTALL(R.string.code_uninstall),
        MOVE_TO_FOLDER(R.string.code_move_to_folder),
        MOVE_TO_ANOTHER_FOLDER(R.string.code_move_to_another_folder),
        REMOVE_FROM_FOLDER(R.string.code_remove_from_folder),
        CUSTOM_COLOR(R.string.code_custom_color),
        RENAME(R.string.code_rename),
        SELECT(R.string.menu_select),
    }

    private fun showAppMenu(app: AppInfo) {
        val isPinned = prefs.isPinned(app.key)
        val pinAction = if (isPinned) AppMenuAction.UNPIN else AppMenuAction.PIN
        val containingFolder = FolderStore.folderContaining(prefs, app.key)
        // Keep action identity independent of translated labels and optional entries.
        val actions = buildList {
            add(pinAction)
            add(AppMenuAction.APP_INFO)
            add(AppMenuAction.HIDE)
            // ACTION_DELETE carries no user, so for a work app it would silently target the
            // personal copy - the one destructive cross-profile intent with no way to aim it.
            // App Info still exposes the system's own uninstall where policy allows it.
            add(AppMenuAction.UNINSTALL)
            if (containingFolder != null) {
                add(AppMenuAction.MOVE_TO_ANOTHER_FOLDER)
                add(AppMenuAction.REMOVE_FROM_FOLDER)
            } else {
                add(AppMenuAction.MOVE_TO_FOLDER)
            }
            add(AppMenuAction.CUSTOM_COLOR)
            add(AppMenuAction.RENAME)
            add(AppMenuAction.SELECT)
        }
        SlateListDialog(
            context = requireContext(),
            title = app.name,
            items = actions.map { getString(it.label) },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (actions[index]) {
                AppMenuAction.PIN -> {
                    // Remove from folder FIRST so the "pinned ⊥ in-folder" invariant holds at
                    // every persistence intermediate, never just at the end of the sequence.
                    FolderStore.removeAppFromFolder(prefs, app.key)
                    prefs.pinApp(app.key)
                    finishFolderChange()
                }
                AppMenuAction.UNPIN -> { prefs.unpinApp(app.key); buildAppList() }
                AppMenuAction.APP_INFO -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                AppMenuAction.HIDE -> {
                    prefs.hideApp(app.key)
                    val removedShortcuts = PinnedShortcutStore.removeForPackage(prefs, launcherApps(), app.packageName)
                    buildAppList()
                    if (removedShortcuts.isNotEmpty()) {
                        showShortcutsRemovedForHiddenAppDialog(app.name, removedShortcuts.size)
                    }
                }
                AppMenuAction.UNINSTALL -> startActivity(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                AppMenuAction.MOVE_TO_FOLDER, AppMenuAction.MOVE_TO_ANOTHER_FOLDER -> showMoveToFolderDialog(app)
                AppMenuAction.REMOVE_FROM_FOLDER -> {
                    FolderStore.removeAppFromFolder(prefs, app.key)
                    finishFolderChange()
                }
                AppMenuAction.CUSTOM_COLOR -> showAppColorPicker(app)
                AppMenuAction.RENAME -> showRenameDialog(app)
                AppMenuAction.SELECT -> { selectedKeys.add(app.key); buildAppList() }
            }
        }.show()
    }

    /**
     * Shown after hiding an app that had one or more pinned shortcuts. Hiding removes those
     * shortcuts outright (both destinations) rather than merely suppressing them, since a
     * shortcut into an app the user just chose not to see would be a confusing loose end.
     */
    private fun showShortcutsRemovedForHiddenAppDialog(appName: String, count: Int) {
        SlateListDialog(
            context = requireContext(),
            title = getString(R.string.code_shortcuts_removed),
            items = listOf(
                resources.getQuantityString(R.plurals.shortcuts_removed_message, count, appName, count),
                getString(R.string.ui_ok)
            ),
            bgColor = prefs.backgroundColor
        ) { _, _ -> }.show()
    }

    private fun showMoveToFolderDialog(app: AppInfo) = showFolderPicker { id ->
        FolderStore.addAppToFolder(prefs, id, app.key)
        finishFolderChange()
    }

    private fun showFolderPicker(onPicked: (String) -> Unit) {
        val existing = FolderStore.all(prefs)
        SlateListDialog(
            context = requireContext(),
            title = getString(R.string.code_move_to_folder),
            items = existing.map { it.name } + getString(R.string.code_new_folder),
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            if (index < existing.size) onPicked(existing[index].id)
            else showCreateFolderDialog { name ->
                onPicked(FolderStore.createEmpty(prefs, name).id)
            }
        }.show()
    }

    private fun finishFolderChange() {
        val folderId = currentFolderId
        if (folderId != null && FolderStore.find(prefs, folderId) == null) exitFolder()
        else buildAppList()
    }

    /** Long-press on a folder label - Pin / Rename / Delete / Custom color. */
    private enum class FolderMenuAction(val label: Int) {
        PIN(R.string.code_pin_to_top),
        UNPIN(R.string.code_unpin),
        RENAME(R.string.code_rename),
        CUSTOM_COLOR(R.string.code_custom_color),
        DELETE(R.string.code_delete_folder),
    }

    private fun showFolderMenu(folder: Folder) {
        // Pin sits first and its label toggles, matching showAppMenu. Unlike pinning an app,
        // this touches nothing but the pin set: a folder is a container, so the "pinned apps
        // can't live in folders" invariant has nothing to resolve here.
        val pinAction = if (prefs.isFolderPinned(folder.id)) FolderMenuAction.UNPIN else FolderMenuAction.PIN
        val actions = listOf(pinAction, FolderMenuAction.RENAME, FolderMenuAction.CUSTOM_COLOR, FolderMenuAction.DELETE)
        SlateListDialog(
            context = requireContext(),
            title = folder.name,
            items = actions.map { getString(it.label) },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (actions[index]) {
                FolderMenuAction.PIN -> { prefs.pinFolder(folder.id); buildAppList() }
                FolderMenuAction.UNPIN -> { prefs.unpinFolder(folder.id); buildAppList() }
                FolderMenuAction.RENAME -> showRenameFolderDialog(folder)
                FolderMenuAction.CUSTOM_COLOR -> showFolderColorPicker(folder)
                FolderMenuAction.DELETE -> showDeleteFolderConfirm(folder)
            }
        }.show()
    }

    /** Reusable text-input dialog used by folder creation and folder rename. */
    private fun showFolderNameDialog(
        title: String,
        initial: String = "",
        confirmLabel: String? = null,
        onConfirm: (String) -> Unit
    ) {
        val ctx = requireContext()
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")
        val density = ctx.resources.displayMetrics.density
        val hPad = (24 * density).toInt()
        val vPad = (14 * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                cornerRadius = 12f * density
            }
        }
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(accent)
            setPadding(hPad, vPad, hPad, vPad)
        })

        val inputFill = if (isLight) Color.parseColor("#EBEBEB") else Color.parseColor("#1E1E1E")
        val inputStroke = if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#4A4A4A")
        val input = android.widget.EditText(ctx).apply {
            setText(initial)
            textSize = 17f
            setTextColor(primary)
            setHintTextColor(secondary)
            hint = getString(R.string.code_folder_name)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(inputFill)
                setStroke((1f * density).toInt(), inputStroke)
                cornerRadius = 8f * density
            }
            val inputHPad = (14 * density).toInt()
            val inputVPad = (12 * density).toInt()
            setPadding(inputHPad, inputVPad, inputHPad, inputVPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart = hPad; it.marginEnd = hPad
                it.topMargin = (12 * density).toInt(); it.bottomMargin = (12 * density).toInt()
            }
            selectAll()
        }
        root.addView(input)

        val dialog = Dialog(ctx, R.style.SlateDialogTheme)
        val bHPad = (20 * density).toInt()
        val bVPad = (15 * density).toInt()
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(hPad, 0, hPad, (16 * density).toInt())
        }
        buttonRow.addView(TextView(ctx).apply {
            text = getString(R.string.ui_cancel)
            textSize = 15f
            setTextColor(secondary)
            setPadding(bHPad, bVPad, bHPad, bVPad)
            setOnClickListener { dialog.dismiss() }
        })
        buttonRow.addView(TextView(ctx).apply {
            text = confirmLabel ?: getString(R.string.pin_save)
            textSize = 15f
            setTextColor(accent)
            setPadding(bHPad, bVPad, bHPad, bVPad)
            setOnClickListener {
                val typed = input.text.toString().trim()
                if (typed.isEmpty()) {
                    Toast.makeText(ctx, getString(R.string.code_name_can_t_be_empty), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(typed)
            }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        // Tell the window manager to bring the IME up alongside the dialog. The postDelayed
        // showSoftInput is a belt-and-suspenders fallback for OEMs that ignore the soft-input
        // mode hint.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun showCreateFolderDialog(onCreated: (String) -> Unit) {
        showFolderNameDialog(
            title = getString(R.string.code_new_folder_2),
            confirmLabel = getString(R.string.action_create),
            onConfirm = onCreated
        )
    }

    private fun showRenameFolderDialog(folder: Folder) {
        showFolderNameDialog(
            title = getString(R.string.code_rename_folder),
            initial = folder.name,
            confirmLabel = getString(R.string.pin_save)
        ) { newName ->
            FolderStore.rename(prefs, folder.id, newName)
            buildAppList()
        }
    }

    private fun showFolderColorPicker(folder: Folder) {
        ColorPickerDialog(
            context = requireContext(),
            title = getString(R.string.code_folder_color),
            initialColor = folder.color ?: prefs.folderTextColor ?: prefs.appTextColor,
            bgColor = prefs.backgroundColor,
            showReset = folder.color != null,
            onReset = { FolderStore.setColor(prefs, folder.id, null); buildAppList() }
        ) { hex ->
            FolderStore.setColor(prefs, folder.id, hex)
            buildAppList()
        }.show()
    }

    private fun showDeleteFolderConfirm(folder: Folder) = showConfirmDialog(
        getString(R.string.code_delete_folder),
        getString(R.string.folder_delete_prompt, folder.name),
        getString(R.string.code_delete)
    ) {
        FolderStore.delete(prefs, folder.id)
        finishFolderChange()
    }

    private fun showConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit) {
        // Reuses the accessibility-info dialog layout (title / body / two buttons) so the
        // confirm is unambiguous and doesn't render the body as a tappable list row.
        val dialog = Dialog(requireContext(), R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup
            ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }
        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = title
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = body
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = confirmLabel
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = dialog
        dialog.setOnDismissListener { if (activeConfirmDialog === dialog) activeConfirmDialog = null }
        dialog.show()
    }

    private fun showRenameDialog(app: AppInfo) {
        val ctx = requireContext()
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")
        val dividerColor = if (isLight) Color.parseColor("#DDDDDD") else Color.parseColor("#333333")
        val ripple = if (isLight) Color.parseColor("#15000000") else Color.parseColor("#20FFFFFF")
        val density = ctx.resources.displayMetrics.density
        val hPad = (24 * density).toInt()
        val vPad = (14 * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                cornerRadius = 12f * density
            }
        }

        // Title
        root.addView(TextView(ctx).apply {
            text = getString(R.string.rename_app_title, app.name)
            textSize = 15f
            setTextColor(accent)
            setPadding(hPad, vPad, hPad, vPad)
        })

        fun divider() = View(ctx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.marginStart = hPad; it.marginEnd = hPad }
        }

        root.addView(divider())

        // Text input - filled background so it reads as an editable field
        val inputFill = if (isLight) Color.parseColor("#EBEBEB") else Color.parseColor("#1E1E1E")
        val inputStroke = if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#4A4A4A")
        val input = android.widget.EditText(ctx).apply {
            setText(app.name)
            textSize = 17f
            setTextColor(primary)
            setHintTextColor(secondary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(inputFill)
                setStroke((1f * density).toInt(), inputStroke)
                cornerRadius = 8f * density
            }
            val inputHPad = (14 * density).toInt()
            val inputVPad = (12 * density).toInt()
            setPadding(inputHPad, inputVPad, inputHPad, inputVPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart = hPad
                it.marginEnd = hPad
                it.topMargin = (12 * density).toInt()
                it.bottomMargin = (12 * density).toInt()
            }
            selectAll()
        }
        root.addView(input)

        val dialog = Dialog(ctx, R.style.SlateDialogTheme)

        val hasCustomName = prefs.getAppCustomName(app.key) != null
        val saveBg   = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val resetBg  = if (isLight) Color.parseColor("#DEDEDE") else Color.parseColor("#2A2A2A")

        val bVPad = (15 * density).toInt()
        val bHPad = (20 * density).toInt()

        fun pillButton(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit) =
            TextView(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(textColor)
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(bgColor)
                    cornerRadius = 100f * density
                }
                setPadding(bHPad, bVPad, bHPad, bVPad)
                setOnClickListener { onClick(); dialog.dismiss() }
            }

        // Horizontal button row
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart  = hPad
                it.marginEnd    = hPad
                it.topMargin    = (10 * density).toInt()
                it.bottomMargin = (20 * density).toInt()
            }
        }

        if (hasCustomName) {
            buttonRow.addView(
                pillButton(getString(R.string.code_reset_to_default), resetBg, secondary) {
                    prefs.clearAppCustomName(app.key)
                    buildAppList()
                }.also {
                    it.layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                        lp.marginEnd = (12 * density).toInt()
                    }
                }
            )
        }

        buttonRow.addView(
            pillButton(getString(R.string.pin_save), saveBg, Color.WHITE) {
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.setAppCustomName(app.key, newName)
                    buildAppList()
                }
            }.also {
                it.layoutParams = if (hasCustomName)
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        )

        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        input.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 80)
    }

    private fun showAppColorPicker(app: AppInfo) {
        val current = prefs.getAppTextColor(app.key) ?: prefs.appTextColor
        ColorPickerDialog(
            context = requireContext(),
            title = app.name,
            initialColor = current,
            bgColor = prefs.backgroundColor,
            showReset = prefs.getAppTextColor(app.key) != null,
            onReset = {
                prefs.clearAppTextColor(app.key)
                buildAppList()
            }
        ) { hex ->
            prefs.setAppTextColor(app.key, hex)
            buildAppList()
        }.show()
    }

    // ── Home long-press dialog ────────────────────────────────────

    /**
     * The home long-press menu itself is gated at its call site (onLongPress). This dialog's
     * own getString(R.string.code_hidden_apps) branch keeps its independent AuthGate.authenticate check regardless -
     * deliberately NOT skipped even when the outer gate just passed, because both checks
     * verifying the "same" PIN is an assumption that would break the moment Hidden Apps gets
     * its own separate PIN (a possible future feature); keeping the two checks fully
     * independent now means neither has to change if that happens later. The cost is a second
     * prompt when both locks are on, accepted deliberately in exchange for that decoupling.
     */
    private fun showHomeLongPressDialog() {
        SlateListDialog(
            context = requireContext(),
            title = "",
            items = listOf(getString(R.string.code_customize), getString(R.string.code_hidden_apps), getString(R.string.code_faq)),
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (index) {
                0 -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
                1 -> AuthGate.authenticate(
                    activity = requireActivity(),
                    prefs = prefs,
                    pinManager = PinManager(prefs),
                    title = getString(R.string.code_hidden_apps),
                    onSuccess = { showHiddenAppsDialog() }
                )
                2 -> showFaqDialog()
            }
        }.show()
    }

    private fun showFaqDialog() {
        val faqs = listOf(
            R.string.faq_accessibility_q to R.string.faq_accessibility_a,
            R.string.faq_data_q to R.string.faq_data_a,
            R.string.faq_permissions_q to R.string.faq_permissions_a,
            R.string.faq_hidden_q to R.string.faq_hidden_a,
            R.string.faq_menu_q to R.string.faq_menu_a,
            R.string.faq_recents_q to R.string.faq_recents_a,
            R.string.faq_folders_q to R.string.faq_folders_a,
            R.string.faq_connectivity_q to R.string.faq_connectivity_a,
            R.string.faq_source_q to R.string.faq_source_a
        )
        SlateListDialog(
            context = requireContext(),
            title = getString(R.string.code_faq),
            items = faqs.map { getString(it.first) },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            showFaqDetail(getString(faqs[index].first), getString(faqs[index].second))
        }.show()
    }

    private fun showFaqDetail(question: String, answer: String) {
        val ctx = requireContext()
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primaryColor = if (isLight) Color.BLACK else Color.WHITE
        val accentColor = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = ctx.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        // Defensive: drop any prior detail dialog before opening a new one.
        activeFaqDetailDialog?.let { runCatching { it.dismiss() } }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                cornerRadius = 12f * density
            }
            setPadding(pad, pad, pad, pad)
        }

        val dialog = Dialog(ctx, R.style.SlateDialogTheme)

        // Back arrow row - stays pinned at the top of the dialog; doesn't scroll with the
        // answer body so the user can always return to the FAQ list mid-read.
        val mutedColor = if (isLight) Color.parseColor("#666666") else Color.parseColor("#888888")
        container.addView(TextView(ctx).apply {
            text = getString(R.string.faq_back)
            textSize = 13f
            setTextColor(mutedColor)
            setPadding((4 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * density).toInt() }
            setOnClickListener {
                dialog.dismiss()
                showFaqDialog()
            }
        })

        // Question - also pinned. Stays visible above the answer so the user keeps the
        // context of what they tapped while reading a long answer.
        container.addView(TextView(ctx).apply {
            text = question
            textSize = 15f
            setTextColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (14 * density).toInt() }
        })

        // Answer is the only scrollable region. Convention follows WidgetArrangeDialog /
        // WidgetPickerDialog / PrivacyPolicyDialog - dialog window is sized to a fixed
        // fraction of the screen (below) and an internal ScrollView with weight=1 absorbs
        // any overflow from the body. Previously this region was a bare TextView inside a
        // WRAP_CONTENT dialog, so long answers (e.g. "How does the hidden apps lock work?")
        // were clipped at the screen edge with no way to read past the cut-off.
        val answerScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(TextView(ctx).apply {
                text = answer
                textSize = 15f
                setTextColor(primaryColor)
                setLineSpacing(4f * density, 1f)
                // Detect bare URLs in the answer body (e.g. the GitHub link in the
                // open-source FAQ) and turn them into tappable links. Linkify auto-installs
                // LinkMovementMethod, so taps open the URL externally without enabling text
                // selection on the rest of the body.
                setLinkTextColor(accentColor)
                android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            })
        }
        container.addView(answerScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply { weight = 1f })

        // MATCH_PARENT on the container so its children's weight=1 has a bounded parent to
        // distribute against - without this the LinearLayout would only be as tall as its
        // natural content and weight=1 would have no effect.
        dialog.setContentView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
            (ctx.resources.displayMetrics.heightPixels * 0.80).toInt()
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (activeFaqDetailDialog === dialog) activeFaqDetailDialog = null
        }
        activeFaqDetailDialog = dialog
        dialog.show()
    }

    private fun showHiddenAppsDialog() {
        // hiddenApps is key-space. The label lookup is OS-facing so it takes the bare package;
        // the pair's second element stays a key, because unhideApp() below needs a key.
        val hidden = prefs.hiddenApps.mapNotNull { key ->
            appLabelFor(key)?.let { it to key }
        }
            .sortedBy { it.first.lowercase() }

        if (hidden.isEmpty()) {
            SlateListDialog(
                context = requireContext(),
                title = getString(R.string.code_hidden_apps),
                items = listOf(getString(R.string.code_no_hidden_apps)),
                bgColor = prefs.backgroundColor
            ) { _, _ -> }.show()
            return
        }

        // Forward-reference the dialog so the long-press → confirm path can dismiss it on
        // successful unhide. The lambda only fires after the user interacts, by which time
        // `parent` is set; the nullable type is a Kotlin formality for the self-reference.
        var parent: SlateListDialog? = null
        parent = SlateListDialog(
            context = requireContext(),
            title = getString(R.string.code_hidden_apps_tap_to_open_hold_to_unhide),
            items = hidden.map { it.first },
            bgColor = prefs.backgroundColor,
            onItemLongPress = { index, _ ->
                showUnhideConfirm(
                    name = hidden[index].first,
                    key = hidden[index].second
                ) {
                    parent?.dismiss()
                    buildAppList()
                }
            }
        ) { index, _ ->
            launchHiddenApp(hidden[index].second)
            // SlateListDialog auto-dismisses after the tap callback.
        }
        parent.show()
    }

    /**
     * Launch a package from the Hidden Apps dialog. Mirrors [launchApp] but accepts a raw
     * package - the Hidden Apps dialog tracks (displayName, pkg) pairs rather than full
     * [AppInfo] objects. The null-intent branch is defensive: the list is filtered for
     * installed apps at open time, so this only trips if an uninstall raced with the tap.
     */
    private fun launchHiddenApp(key: String) {
        val intent = requireContext().packageManager
            .getLaunchIntentForPackage(AppKey.packageOf(key))
        if (intent == null) {
            Toast.makeText(requireContext(), getString(R.string.code_app_not_installed), Toast.LENGTH_SHORT).show()
            return
        }
        // Privacy: keep hidden-app launches off the system Recents / Overview screen so a
        // coworker glancing at Recents can't see what hidden app was opened. The flag applies
        // at task-creation time; if the target app uses launchMode="singleTask" and already
        // has a live task in Recents from before, that existing task is reused and stays
        // visible - public APIs don't let a third-party launcher remove another app's task.
        // The FAQ explains the one-time-swipe mitigation to the user.
        //
        // The flag costs more than visibility: from Android 9 the system trims an excluded task
        // once it falls behind home, and trimming FINISHES its activities, so switching away
        // destroys the app and anything typed into it. keepHiddenAppsInRecents lets a user who
        // needs that state preserved opt out. Default false, and gated behind an explicit
        // consent dialog in Settings, so the privacy behaviour is unchanged unless chosen.
        if (!prefs.keepHiddenAppsInRecents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), getString(R.string.code_app_not_installed), Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Confirmation dialog before unhiding an app - guards against a misclick on the Hidden
     * Apps long-press. Reuses the accessibility-info dialog layout (title / body / two
     * buttons), the same template as [showDeleteFolderConfirm].
     */
    private fun showUnhideConfirm(name: String, key: String, onConfirmed: () -> Unit) {
        val dialog = Dialog(requireContext(), R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup
            ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }
        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.code_unhide_app)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.unhide_prompt, name)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.code_unhide)
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.unhideApp(key)
                onConfirmed()
            }
        }
        dialog.show()
    }

    // ── System actions ────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val wm = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = !wm.isWifiEnabled
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun toggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No Bluetooth panel in Settings.Panel; open Bluetooth settings page
            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter?.isEnabled == true) adapter.disable() else adapter?.enable()
        }
    }

    private fun lockScreen() {
        SlateAccessibilityService.lockScreen()
    }

    private fun expandNotificationsPanel() {
        try {
            val service = requireContext().getSystemService("statusbar") ?: return
            val manager = Class.forName("android.app.StatusBarManager")
            manager.getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {}
    }
}
