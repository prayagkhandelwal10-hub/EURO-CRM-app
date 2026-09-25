package com.kushal.eurocall

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a finished recording to the EURO backend, which runs
 * speech-to-text -> translate -> summarize -> attach to the matching lead.
 *
 * Backend contract (see backend-reference/server.py):
 *   POST {backendUrl}/api/calls/upload   (multipart/form-data)
 *     audio=<file .m4a>
 *     label=<Cellular|WhatsApp|Viber|...>
 *     number=<optional phone number>
 *     my_lang=<e.g. en>
 *     their_lang=<e.g. auto>
 *   -> 200 { transcript, translation, summary, action_items[], lead_id }
 */
object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun enqueue(ctx: Context, file: File, label: String, number: String) {
        val backend = Prefs.get(ctx, Prefs.BACKEND_URL, "").trimEnd('/')
        if (backend.isEmpty()) {
            Log.w("EuroCall", "No backend URL set; recording saved locally at ${file.absolutePath}")
            return
        }
        val myLang = Prefs.get(ctx, Prefs.MY_LANG, "en")
        val theirLang = Prefs.get(ctx, Prefs.THEIR_LANG, "auto")

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("label", label)
            .addFormDataPart("number", number)
            .addFormDataPart("my_lang", myLang)
            .addFormDataPart("their_lang", theirLang)
            .build()

        val req = Request.Builder().url("$backend/api/calls/upload").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EuroCall", "Upload failed: ${e.message}. File kept at ${file.absolutePath}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.i("EuroCall", "Upload ok (${it.code}). Summary synced to CRM.")
                }
            }
        })
    }
}
