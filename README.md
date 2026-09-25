# EURO Call — internal call assistant (Android 14)

An internal Android app for the EURO CRM. It records call audio, sends it to your
EURO backend, which transcribes, translates and summarizes the call and files a note
against the matching lead. Built for your own device (Samsung Galaxy S23, Android 14).
Not for the Play Store — sideload only.

## What it does
- One-tap (or auto) recording of call audio via a foreground service.
- Auto start/stop on **cellular** calls (phone-state receiver) and on **VoIP** calls —
  WhatsApp / Viber / Dialpad — by watching their ongoing-call notifications.
- After each call, uploads the recording to your backend → transcript → translation →
  summary + action items → note pushed to the lead in EURO.
- Settings for backend URL and languages (yours / theirs, `auto` = detect).

## How to build the APK
1. Install **Android Studio** (Koala or newer).
2. `File > Open` this folder. Let Gradle sync (it pulls the Android Gradle Plugin 8.5.2,
   Kotlin 1.9.24, compileSdk 34).
3. First time only, generate the Gradle wrapper if prompted: run `gradle wrapper` in a
   terminal, or just let Android Studio use its bundled Gradle.
4. Plug in the S23 (USB debugging on) and press **Run**, or `Build > Build APK(s)` and
   copy the APK to the phone to install.

## On-phone setup (once)
1. Open the app → **Grant permissions** (microphone, phone, notifications).
2. Tap **Enable VoIP call detection** → turn on notification access for "EURO Call".
3. Enter your **backend URL** and languages → **Save settings**.
4. Keep calls on **speaker** so the mic captures both voices.

## Capture note (important for your developer)
On stock Android the reliable way to get **both** voices without root is the microphone
with the call on **speaker** (`AudioSource.MIC`, no echo cancellation). This works for
cellular **and** VoIP (WhatsApp/Viber/Dialpad). If you run this on a **rooted** S23,
switch `RecorderService.SOURCE` to `MediaRecorder.AudioSource.VOICE_CALL` for a clean
two-channel capture straight from the call stream.

The VoIP auto-detect keys off each app's ongoing-call notification. Notification text
varies by app version and language — tune the `activeHints` / `voipPackages` lists in
`VoipCallListener.kt` to match what your apps actually post. The manual Start/Stop button
always works regardless.

## Live real-time translation (extension)
This build does **record → upload → summarize** (post-call). For **live** two-way
translation *during* the call, add a streaming path: capture PCM with `AudioRecord` and
stream 250 ms chunks over a WebSocket to a streaming STT+translate service (e.g. a
`faster-whisper` streaming server, or SeamlessM4T for speech-to-speech), and show live
captions. The same foreground service can host it; use `AudioRecord` instead of
`MediaRecorder` so you can both write the file and stream chunks.

## Backend
See `backend-reference/server.py` — a FastAPI reference showing the exact upload contract
and the STT → translate → summarize → CRM pipeline. Point the app's backend URL at it.

## Recommended open-source pieces
- STT: `SYSTRAN/faster-whisper`, `ggml-org/whisper.cpp`, Vosk (streaming).
- Translation / speech-to-speech: `facebookresearch/seamless_communication` (SeamlessM4T).
- TTS (for spoken replies): `rhasspy/piper`, Coqui XTTS-v2, MeloTTS.
- Voice-agent orchestration (smooth turn-taking): `pipecat-ai/pipecat`, `livekit/agents`.
- Model access with one API key: OpenRouter (used in the backend reference).
