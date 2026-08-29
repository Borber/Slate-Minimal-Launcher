package com.slate.launcher

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Single entry point for authenticating actions gated by the hidden-apps security feature.
 *
 *   AuthGate.authenticate(activity, prefs, pinManager, title = "Hidden Apps") {
 *       // runs on success
 *   }
 *
 * When biometric is enabled and available, the prompt shows up first with a "Use PIN" fallback
 * button that drops the user into the in-app PIN screen. PIN is always the source of truth;
 * biometric is opt-in.
 *
 * Rate-limiting: only PIN failures count toward the in-app lockout. Biometric failures are
 * handled by the OS-level biometric lockout.
 */
object AuthGate {

    /** Authenticate the user. Calls [onSuccess] on success, [onCancel] otherwise. */
    fun authenticate(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        title: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        if (!prefs.hiddenAppsSecurityEnabled || !pinManager.hasPin()) {
            onSuccess()
            return
        }
        val lockoutMs = pinManager.lockoutMillisRemaining()
        if (lockoutMs > 0) {
            showLockedOutToast(activity, lockoutMs)
            onCancel()
            return
        }
        if (prefs.biometricEnabled && canUseBiometric(activity)) {
            showBiometric(activity, prefs, pinManager, title, onSuccess, onCancel)
        } else {
            PinFlow.verifyExisting(activity, prefs, pinManager, title, onSuccess, onCancel)
        }
    }

    /**
     * Authenticate for a feature that must NEVER use biometric, even if [prefs.biometricEnabled]
     * is on for Hidden Apps - the "Lock long-press" feature is the only caller today.
     *
     * Deliberately a separate function rather than an `allowBiometric` parameter on
     * [authenticate]. This project has no automated tests, so a boolean a future call site could
     * silently pass wrong is a real risk; a function with no biometric branch in its body at all
     * cannot route through biometric by accident, no matter what any future caller does.
     *
     * [enabled] is caller-supplied rather than read from a hardcoded pref, unlike [authenticate],
     * because this function is meant to serve exactly the one caller-chosen feature flag, not a
     * second copy of the Hidden Apps toggle - see [PreferencesManager.lockLongPressMenusEnabled].
     *
     * Fails open exactly like [authenticate]: no PIN configured means every caller of this
     * function must behave as if the feature were off, never as a lock the user cannot pass.
     */
    fun authenticatePinOnly(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        enabled: Boolean,
        title: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        if (!enabled || !pinManager.hasPin()) {
            onSuccess()
            return
        }
        val lockoutMs = pinManager.lockoutMillisRemaining()
        if (lockoutMs > 0) {
            showLockedOutToast(activity, lockoutMs)
            onCancel()
            return
        }
        PinFlow.verifyExisting(activity, prefs, pinManager, title, onSuccess, onCancel)
    }

    /**
     * Returns true if the device has biometric hardware AND at least one biometric is enrolled
     * that meets the BIOMETRIC_STRONG class requirement.
     */
    fun canUseBiometric(ctx: Context): Boolean {
        val mgr = BiometricManager.from(ctx)
        return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Convenience for Settings: prompts biometric so the user proves they own the enrolled finger/face. */
    fun verifyBiometric(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onCancel()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun showBiometric(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        title: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                            PinFlow.verifyExisting(activity, prefs, pinManager, title, onSuccess, onCancel)
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED ->
                            onCancel()
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            PinFlow.verifyExisting(activity, prefs, pinManager, title, onSuccess, onCancel)
                        else -> {
                            Toast.makeText(activity, errString, Toast.LENGTH_SHORT).show()
                            onCancel()
                        }
                    }
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Unlock with biometric")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use PIN")
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun showLockedOutToast(ctx: Context, lockoutMs: Long) {
        val seconds = (lockoutMs / 1000).coerceAtLeast(1)
        val text = if (seconds >= 60) {
            "Too many attempts. Try again in ${seconds / 60} min."
        } else {
            "Too many attempts. Try again in $seconds s."
        }
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }
}
