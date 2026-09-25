# Privacy Policy — Slate Launcher

**Effective date:** 2026-09-25

Slate does not collect, transmit, or share personal data. It has no analytics, advertising, crash reporting, or app-operated network service. App preferences and launch counts stay in the app's private storage on your device.

## Data stored locally

Slate stores launch counts for usage sorting; hidden and pinned apps; app labels and colors; folders and pinned app shortcuts; gesture assignments; appearance and language settings. You can export these settings to a JSON file. Hidden apps, PIN verification data, and biometric preferences are excluded from backups unless you explicitly include them.

## Hidden apps and security

When PIN protection is enabled, Slate stores a salted PBKDF2-HMAC-SHA256 verifier in private storage. The PIN itself is not stored. Biometric matching is handled by Android; Slate receives only the result. An optional setting uses the same PIN to gate long-press menus. Wrong PIN attempts trigger a local timeout.

Hidden apps launched through Slate are excluded from Android Recents by default. You can allow them in Recents in Settings. When importing a backup with private data, Slate verifies the backup PIN in memory before restoring that data.

## Optional system access

- **App list:** Android package visibility lets Slate display installed applications.
- **Screen lock:** The optional accessibility service performs the lock action on double tap. Slate does not use it to read screen content.
- **Lock-screen wallpaper:** If enabled, Slate writes a solid-color image to the lock-screen wallpaper when the background changes.
- **Gestures:** The selected gesture can open system panels, including notifications, Wi-Fi, Bluetooth, or location settings. Older Android versions may permit direct Wi-Fi or Bluetooth toggles.
- **Uninstall:** The app menu opens Android's confirmation dialog; Slate does not uninstall without that confirmation.

Slate does not request contacts, phone-call, or notification-listener access.

## Contact

For questions, open an issue in the project repository.
