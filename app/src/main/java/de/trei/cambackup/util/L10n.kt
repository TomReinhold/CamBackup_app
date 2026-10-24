package de.trei.cambackup.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.io.IOException
import java.util.Locale

/**
 * Texte außerhalb der Oberfläche (Hintergrund-Jobs, Fehlermeldungen). Angezeigt wird in der
 * App-Sprache; Protokoll und Dateiliste auf dem Speichermedium sind immer englisch ([en]).
 */
object L10n {
    private lateinit var app: Context

    /** Englische Texte (Standard-Ressourcen) unabhängig von der App-Sprache. */
    private val english: Context by lazy {
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)
    fun en(@StringRes id: Int, vararg args: Any): String = english.getString(id, *args)
    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String = app.resources.getQuantityString(id, n, *args)
    fun pluralEn(@PluralsRes id: Int, n: Int, vararg args: Any): String = english.resources.getQuantityString(id, n, *args)
}

/** Ein Text, der in der App-Sprache ([ui]) angezeigt und englisch ([en]) protokolliert werden kann. */
sealed interface Msg {
    val ui: String
    val en: String

    class Res(@StringRes private val id: Int, private vararg val args: Any) : Msg {
        override val ui: String get() = L10n.str(id, *args.map { if (it is Msg) it.ui else it }.toTypedArray())
        override val en: String get() = L10n.en(id, *args.map { if (it is Msg) it.en else it }.toTypedArray())
    }

    /** Fremder Text (z. B. Meldung des Systems) – in beiden Fällen unverändert. */
    class Raw(private val text: String) : Msg {
        override val ui: String get() = text
        override val en: String get() = text
    }
}

fun msg(@StringRes id: Int, vararg args: Any): Msg = Msg.Res(id, *args)

/** Fehler mit übersetzbarer Meldung; [message] ist englisch (Protokoll, Logcat). */
class AppError(val msg: Msg, cause: Throwable? = null) : IOException(msg.en, cause) {
    constructor(@StringRes id: Int, vararg args: Any) : this(Msg.Res(id, *args))
}

/** Meldung eines Fehlers als [Msg] – übersetzt, wenn er aus der App stammt. */
fun Throwable.toMsg(): Msg = (this as? AppError)?.msg ?: Msg.Raw(message ?: javaClass.simpleName)

/** Sprache der App: wie das System (Fallback Englisch), Deutsch oder Englisch. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    GERMAN("de"),
    ENGLISH("en");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "tag"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language.orEmpty()
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            }
            return entries.firstOrNull { it.tag == tag && it != SYSTEM } ?: SYSTEM
        }

        /**
         * Ab Android 13 übernimmt das System die App-Sprache (auch in den System-Einstellungen
         * unter „App-Sprachen“) und baut die Oberfläche neu auf; davor merkt sich die App die Wahl.
         */
        fun set(context: Context, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
            }
        }

        /** Vor Android 13: Kontext mit der gewählten Sprache (für Application und Activity). */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            if (tag.isEmpty()) return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        }
    }
}
