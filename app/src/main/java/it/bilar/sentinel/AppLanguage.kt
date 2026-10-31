package it.bilar.sentinel

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

internal object AppLanguage {
    @Volatile private var strings: android.content.res.Resources? = null
    @Volatile private var knownMessages = emptyMap<String,Int>()
    val locale: Locale get() = strings?.configuration?.locales?.get(0) ?: Locale.ENGLISH
    fun code(context: Context?): String = context?.getSharedPreferences("appearance",Context.MODE_PRIVATE)
        ?.getString("language","en")?.takeIf { it in setOf("en","it") } ?: "en"
    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(code(context))
        Locale.setDefault(locale)
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
    fun initialize(context: Context) {
        strings = wrap(context.applicationContext).resources
        val messages = mutableMapOf<String,Int>()
        for(language in listOf("en","it")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            val ids = localized.resources.obtainTypedArray(R.array.localized_messages)
            try { for(i in 0 until ids.length()) { val id = ids.getResourceId(i,0); messages[localized.getString(id)] = id } }
            finally { ids.recycle() }
        }
        knownMessages = messages
    }
    fun set(context: Context, language: String) {
        require(language in setOf("en","it"))
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("language",language).apply()
        initialize(context)
    }
    fun get(@StringRes id: Int, vararg arguments: Any): String =
        (if(arguments.isEmpty()) strings?.getString(id) else strings?.getString(id,*arguments)) ?: "Invalid operation."
    fun quantity(id: Int, count: Int) = strings?.getQuantityString(id,count,count) ?: count.toString()
    fun translate(message: String): String {
        knownMessages[message]?.let { return get(it) }
        Regex("Formato ACK DP non riconosciuto \\(comando (\\d+), (\\d+) byte\\)\\.").matchEntire(message)?.let {
            return get(R.string.engine_ack,it.groupValues[1],it.groupValues[2])
        }
        Regex("Comando rifiutato dal veicolo \\(stato (\\d+)\\)\\.").matchEntire(message)?.let {
            return get(R.string.engine_rejected,it.groupValues[1])
        }
        return message
    }
}

internal fun appText(@StringRes id: Int, vararg arguments: Any) = AppLanguage.get(id,*arguments)
