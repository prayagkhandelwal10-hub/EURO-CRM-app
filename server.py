"""
EURO Call — minimal backend reference (FastAPI).

Receives a call recording from the Android app, runs:
  speech-to-text (faster-whisper, with language detection)
    -> translation (via your LLM through OpenRouter, or any model)
    -> summary + action items
    -> POST the note to the EURO CRM lead (matched by phone number).

This is a REFERENCE to show the contract and the pipeline — wire the CRM call
and model provider to your real system. Run:
    pip install -r requirements.txt
    uvicorn server:app --host 0.0.0.0 --port 8000
"""
import os, tempfile, datetime, requests
from fastapi import FastAPI, UploadFile, Form
from faster_whisper import WhisperModel

app = FastAPI()

# Load once. "small"/"medium" balance speed vs accuracy; use "large-v3" for best quality.
stt = WhisperModel("small", device="cpu", compute_type="int8")

OPENROUTER_KEY = os.getenv("OPENROUTER_API_KEY", "")   # one key -> many models
CRM_BASE = os.getenv("EURO_CRM_URL", "https://your-euro-crm.example.com")
CRM_TOKEN = os.getenv("EURO_CRM_TOKEN", "")


def llm(prompt: str, model: str = "openai/gpt-4o-mini") -> str:
    """Call any model through OpenRouter with a single API key."""
    if not OPENROUTER_KEY:
        return "(LLM key not set)"
    r = requests.post(
        "https://openrouter.ai/api/v1/chat/completions",
        headers={"Authorization": f"Bearer {OPENROUTER_KEY}"},
        json={"model": model, "messages": [{"role": "user", "content": prompt}]},
        timeout=120,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


@app.post("/api/calls/upload")
async def upload(
    audio: UploadFile,
    label: str = Form("Call"),
    number: str = Form(""),
    my_lang: str = Form("en"),
    their_lang: str = Form("auto"),
):
    # 1) Save the uploaded audio
    with tempfile.NamedTemporaryFile(delete=False, suffix=".m4a") as f:
        f.write(await audio.read())
        path = f.name

    # 2) Speech-to-text (auto-detects language)
    segments, info = stt.transcribe(path, beam_size=5)
    transcript = " ".join(s.text.strip() for s in segments)
    detected = info.language

    # 3) Translate to your language (skip if already my_lang)
    translation = transcript
    if detected != my_lang:
        translation = llm(
            f"Translate this call transcript from {detected} into {my_lang}. "
            f"Keep names, numbers, prices and product codes exact.\n\n{transcript}"
        )

    # 4) Summary + action items for the CRM
    summary_json = llm(
        "You are a B2B export sales assistant. From this call, return a short summary, "
        "the customer's requirements/concerns, and clear next actions as bullet points.\n\n"
        f"{translation}"
    )

    # 5) Push a note to the matching lead in EURO (match by phone number)
    note = {
        "type": "call",
        "label": label,
        "number": number,
        "detected_language": detected,
        "transcript": transcript,
        "translation": translation,
        "summary": summary_json,
        "at": datetime.datetime.utcnow().isoformat() + "Z",
    }
    lead_id = None
    if CRM_TOKEN:
        try:
            resp = requests.post(
                f"{CRM_BASE}/api/leads/match-and-note",
                headers={"Authorization": f"Bearer {CRM_TOKEN}"},
                json=note, timeout=30,
            )
            lead_id = resp.json().get("lead_id")
        except Exception as e:
            print("CRM sync failed:", e)

    os.remove(path)
    return {
        "detected_language": detected,
        "transcript": transcript,
        "translation": translation,
        "summary": summary_json,
        "lead_id": lead_id,
    }
