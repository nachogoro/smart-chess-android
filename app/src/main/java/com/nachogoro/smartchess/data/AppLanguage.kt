package com.nachogoro.smartchess.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The language the app is shown in.
 *
 * Deliberately not part of [AppSettings]. AppCompat already stores this (in the framework on
 * API 33 and later, and in its own preferences below that) and a second copy in DataStore could
 * only ever disagree. Reading it back from AppCompat means the in-app picker and Android 13's
 * own per-app language screen are the same setting.
 */
enum class AppLanguage(val tag: String?) {
    /** Whatever the device is set to, falling back to English where there is no translation. */
    SYSTEM(null),
    ENGLISH("en"),
    SPANISH("es");

    companion object {
        /**
         * The language in force.
         *
         * An empty locale list means no per-app choice has been made, which is [SYSTEM].
         */
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales()
                .takeUnless { it.isEmpty }
                ?.get(0)
                ?.language
            return entries.firstOrNull { it.tag == tag } ?: SYSTEM
        }

        /**
         * Switches the app to [language].
         *
         * The activity is recreated as a result, so Compose re-reads every string; nothing else
         * has to be told. Must be called from the main thread.
         */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                language.tag
                    ?.let { LocaleListCompat.forLanguageTags(it) }
                    ?: LocaleListCompat.getEmptyLocaleList()
            )
        }
    }
}
