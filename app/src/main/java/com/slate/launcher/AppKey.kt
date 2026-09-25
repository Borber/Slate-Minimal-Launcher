package com.slate.launcher

/**
 * App identity across user profiles. The main profile (serial == null) encodes to the bare
 * package name, so every string written to SharedPreferences by a pre-work-profile build
 * round-trips byte-identically. '@' cannot occur in an Android package name.
 *
 * [serialOf] is total: any malformed suffix parses as null, i.e. main profile. Legacy strings,
 * hand-edited backups and future unknown values all degrade to today's behaviour, never throw.
 */
object AppKey {
    private const val SEP = '@'

    fun of(packageName: String, serial: Long?): String =
        if (serial == null) packageName else "$packageName$SEP$serial"

    fun packageOf(key: String): String =
        if (serialOf(key) == null) key else key.substringBeforeLast(SEP)

    fun serialOf(key: String): Long? =
        key.substringAfterLast(SEP, "").takeIf { it.isNotEmpty() }?.toLongOrNull()
}
