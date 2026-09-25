package com.kushal.eurocall

import android.content.Context

/** Simple settings store: backend URL, languages, auto-detect toggle. */
object Prefs {
    private const val FILE = "eurocall_prefs"

    fun get(ctx: Context, key: String, def: String): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun set(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    fun getBool(ctx: Context, key: String, def: Boolean): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, def)

    fun setBool(ctx: Context, key: String, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    // Keys
    const val BACKEND_URL = "backend_url"
    const val MY_LANG = "my_lang"
    const val THEIR_LANG = "their_lang"
    const val AUTO_DETECT = "auto_detect"
}
