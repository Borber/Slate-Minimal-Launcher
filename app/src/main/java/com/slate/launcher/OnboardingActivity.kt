package com.slate.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.checkbox.MaterialCheckBox

class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var cardDark: LinearLayout
    private lateinit var cardLight: LinearLayout
    private lateinit var cardFlow: LinearLayout
    private lateinit var cardList: LinearLayout
    private lateinit var checkPrivacy: MaterialCheckBox
    private lateinit var btnSetDefault: TextView
    private lateinit var btnSkip: TextView
    private lateinit var btnImport: TextView

    // 0 = dark selected, 1 = light selected
    private var selectedTheme = 0

    // LAYOUT_FLOW or LAYOUT_LIST. Seeded from prefs in onCreate, not assumed.
    private var selectedLayout = LAYOUT_FLOW

    companion object {
        private const val STATE_PRIVACY_CHECKED = "privacy_checked"
        private const val STATE_SELECTED_THEME = "selected_theme"
        private const val STATE_SELECTED_LAYOUT = "selected_layout"
        private const val LINK_COLOR = "#8888FF"

        // Indices into listOf(cardFlow, cardList), the same way selectedTheme indexes themes.
        private const val LAYOUT_FLOW = 0
        private const val LAYOUT_LIST = 1

        /**
         * What each layout card writes besides the mode itself - see [applySelectedLayout].
         *
         * A list on the stock settings is centred, with every row at the 42sp maximum (list
         * mode draws all rows at maxFontSize), which is nothing like the compact left-aligned
         * column the card shows. So choosing Minimal List here also writes the two values that
         * make day one match the preview. Flow's pair is simply the stock values, so that
         * switching back within this screen undoes the list pair instead of leaving it behind.
         */
        private const val LIST_ALIGNMENT = "left"
        private const val LIST_FONT_SIZE = 24
        private const val FLOW_ALIGNMENT = "center"
        private const val FLOW_FONT_SIZE = PreferencesManager.DEFAULT_MAX_FONT_SIZE
    }

    private data class Theme(
        val bgColor: String,
        val textColor: String,
        val cardFill: Int,
        val strokeSelected: Int,
        val strokeUnselected: Int
    )

    private val themes = listOf(
        Theme(
            bgColor = "#000000",
            textColor = "#808080",
            cardFill = Color.parseColor("#0D0D0D"),
            strokeSelected = Color.parseColor("#8888FF"),
            strokeUnselected = Color.parseColor("#333333")
        ),
        Theme(
            bgColor = "#FFFFFF",
            textColor = "#333333",
            cardFill = Color.parseColor("#F5F5F5"),
            strokeSelected = Color.parseColor("#333399"),
            strokeUnselected = Color.parseColor("#DDDDDD")
        )
    )

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Re-verify consent - the picker callback can fire after the user has unchecked the box
        // (e.g., they backgrounded onboarding while the picker was open).
        if (!hasAcceptedPrivacy()) {
            Toast.makeText(this, "Please accept the privacy policy first", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            val mgr = BackupManager(prefs)
            val contents = mgr.parse(json)
            mgr.applyNonPrivate(contents)
            // Onboarding deliberately skips the private bundle (hidden apps + PIN + biometric).
            // The full PIN-verify dialog flow lives in Settings → Backup → Import; surfacing it
            // mid-onboarding would gate the welcome flow behind a PIN the user may not remember.
            // The user can re-import the same backup from Settings later to restore the
            // private bundle through the standard PIN-verify path.
            val skippedNote =
                if (contents.privateBundle != null)
                    "Settings restored. Re-import from Settings to restore hidden apps."
                else "Settings restored"
            Toast.makeText(this, skippedNote, Toast.LENGTH_LONG).show()
            finishOnboarding()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasAcceptedPrivacy(): Boolean =
        ::checkPrivacy.isInitialized && checkPrivacy.isChecked

    // onResume detects acceptance; callback handles denial (user backed out without selecting).
    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            return@registerForActivityResult
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        setContentView(R.layout.activity_onboarding)
        supportActionBar?.hide()

        cardDark = findViewById(R.id.cardDark)
        cardLight = findViewById(R.id.cardLight)
        cardFlow = findViewById(R.id.cardFlow)
        cardList = findViewById(R.id.cardList)

        // Start from the layout already in prefs rather than assuming Flow. This screen can be
        // reached by someone who has used Slate before: if it was already the default launcher
        // on first launch, MainActivity skipped onboarding without marking it complete, and it
        // appears later once another launcher takes over. Their layout must not flip unless
        // they pick the other card.
        selectedLayout =
            if (prefs.homescreenView == PreferencesManager.VIEW_LIST) LAYOUT_LIST else LAYOUT_FLOW

        // This activity is not orientation-locked, so both choices have to survive a rotation.
        // Only the consent checkbox used to; the theme choice silently fell back to Dark.
        savedInstanceState?.let {
            selectedTheme = it.getInt(STATE_SELECTED_THEME, selectedTheme)
            selectedLayout = it.getInt(STATE_SELECTED_LAYOUT, selectedLayout)
        }

        // The same four settings renderFlowMode applies to the real home screen, so the
        // miniature wraps and centres the way Flow itself does at any card width.
        findViewById<FlexboxLayout>(R.id.previewFlow).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            alignItems = AlignItems.CENTER
            justifyContent = JustifyContent.CENTER
        }

        updateCardStyles()
        styleActionButton()

        cardDark.setOnClickListener {
            selectedTheme = 0
            updateCardStyles()
        }

        cardLight.setOnClickListener {
            selectedTheme = 1
            updateCardStyles()
        }

        cardFlow.setOnClickListener {
            selectedLayout = LAYOUT_FLOW
            updateCardStyles()
        }

        cardList.setOnClickListener {
            selectedLayout = LAYOUT_LIST
            updateCardStyles()
        }

        btnSetDefault = findViewById(R.id.btnSetDefault)
        btnSkip = findViewById(R.id.btnSkip)
        btnImport = findViewById(R.id.btnImportSettings)

        btnSetDefault.setOnClickListener {
            applySelectedTheme()
            applySelectedLayout()
            requestDefaultLauncher()
        }

        btnSkip.setOnClickListener {
            applySelectedTheme()
            applySelectedLayout()
            finishOnboarding()
        }

        btnImport.setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/json", "*/*"))
        }

        setupPrivacyConsent(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_THEME, selectedTheme)
        outState.putInt(STATE_SELECTED_LAYOUT, selectedLayout)
        if (::checkPrivacy.isInitialized) {
            outState.putBoolean(STATE_PRIVACY_CHECKED, checkPrivacy.isChecked)
        }
    }

    private fun setupPrivacyConsent(savedInstanceState: Bundle?) {
        checkPrivacy = findViewById(R.id.checkPrivacy)
        val label = findViewById<TextView>(R.id.labelPrivacyAcceptance)

        val text = "I've read the Privacy Policy"
        val linkStart = text.indexOf("Privacy Policy")
        val span = SpannableString(text)
        span.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                PrivacyPolicyDialog.show(this@OnboardingActivity)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = Color.parseColor(LINK_COLOR)
                ds.isUnderlineText = true
            }
        }, linkStart, linkStart + "Privacy Policy".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.text = span
        label.movementMethod = LinkMovementMethod.getInstance()

        val initialChecked = savedInstanceState?.getBoolean(STATE_PRIVACY_CHECKED, false) ?: false
        checkPrivacy.isChecked = initialChecked
        updateActionsEnabled(initialChecked)

        checkPrivacy.setOnCheckedChangeListener { _, isChecked ->
            updateActionsEnabled(isChecked)
        }
    }

    private fun updateActionsEnabled(enabled: Boolean) {
        val targets = listOf(btnSetDefault, btnSkip, btnImport)
        targets.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    override fun onResume() {
        super.onResume()
        // Gate auto-completion on the consent checkbox so users who set Slate as default outside
        // our flow (Android Settings) - or who back-out of the role picker after unchecking the
        // box - still have to accept the policy before onboarding completes.
        if (isDefaultLauncher() && hasAcceptedPrivacy()) {
            finishOnboarding()
        }
    }

    override fun onDestroy() {
        // Prevent android.view.WindowLeaked if the privacy dialog is open during rotation /
        // configuration change.
        PrivacyPolicyDialog.dismissActive()
        super.onDestroy()
    }

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        return info?.activityInfo?.packageName == packageName
    }

    private fun applySelectedTheme() {
        val theme = themes[selectedTheme]
        prefs.backgroundColor = theme.bgColor
        prefs.appTextColor = theme.textColor
    }

    /**
     * Writes the chosen layout as a complete preset, and only when it differs from the layout
     * already in prefs.
     *
     * "Complete" because "Set as default launcher" applies the choice and then leaves for the
     * system role picker, which the user can cancel, come back from, pick the other card and
     * tap again. If Flow wrote only the mode, that second tap would leave Minimal List's
     * alignment and size behind on a Flow home screen. Each card therefore writes all three
     * values, so the last tap alone decides the result.
     *
     * "Only when it differs" so that keeping the layout you already have costs nothing: a
     * fresh install that stays on Flow writes no prefs at all, and someone reaching this screen
     * with a layout they have already tuned keeps their alignment and size unless they switch.
     *
     * The Import path deliberately never calls this, exactly as it never calls
     * [applySelectedTheme] - a restored backup outranks both cards.
     */
    private fun applySelectedLayout() {
        val chosen =
            if (selectedLayout == LAYOUT_LIST) PreferencesManager.VIEW_LIST
            else PreferencesManager.VIEW_FLOW
        if (prefs.homescreenView == chosen) return

        prefs.homescreenView = chosen
        if (chosen == PreferencesManager.VIEW_LIST) {
            prefs.textAlignment = LIST_ALIGNMENT
            prefs.maxFontSize = LIST_FONT_SIZE
        } else {
            prefs.textAlignment = FLOW_ALIGNMENT
            prefs.maxFontSize = FLOW_FONT_SIZE
        }
    }

    private fun styleActionButton() {
        val density = resources.displayMetrics.density
        findViewById<TextView>(R.id.btnSetDefault).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(Color.TRANSPARENT)
            setStroke((1.5f * density).toInt(), Color.parseColor("#8888FF"))
        }
    }

    private fun updateCardStyles() {
        listOf(cardDark, cardLight).forEachIndexed { index, card ->
            card.background = cardBackground(themes[index], index == selectedTheme)
        }

        // The layout cards wear whichever theme is selected above, so the two rows read as one
        // combined preview of the home screen rather than as two unrelated questions. Their
        // colours come from the same Theme values the theme cards hardcode in XML: the label
        // takes the accent (strokeSelected) and the names take textColor.
        val theme = themes[selectedTheme]
        val nameColor = Color.parseColor(theme.textColor)
        listOf(cardFlow, cardList).forEachIndexed { index, card ->
            val isSelected = index == selectedLayout
            card.background = cardBackground(theme, isSelected)
            // The stroke is the only visible sign of the choice, and a screen reader cannot
            // see a stroke. The selected state is what lets TalkBack say which card is chosen.
            card.isSelected = isSelected
        }
        findViewById<TextView>(R.id.labelFlow).setTextColor(theme.strokeSelected)
        findViewById<TextView>(R.id.labelList).setTextColor(theme.strokeSelected)
        findViewById<TextView>(R.id.captionFlow).setTextColor(nameColor)
        tintNames(findViewById(R.id.previewFlow), nameColor)
        tintNames(findViewById(R.id.previewList), nameColor)
    }

    private fun cardBackground(theme: Theme, isSelected: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        val stroke = if (isSelected) theme.strokeSelected else theme.strokeUnselected
        val strokeWidth = if (isSelected) 2f else 1f
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(theme.cardFill)
            setStroke((strokeWidth * density).toInt(), stroke)
        }
    }

    private fun tintNames(group: ViewGroup, color: Int) {
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? TextView)?.setTextColor(color)
        }
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestRoleLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                )
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun finishOnboarding() {
        prefs.onboardingComplete = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
