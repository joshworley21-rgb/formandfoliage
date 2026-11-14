// Natural voice for the voice assistant.
//
// Text goes in, an MP3 comes out. The OpenAI key stays in Supabase secrets;
// the app falls back to the on-device Kokoro voice whenever this is offline or
// fails, so the assistant is never mute.
//
// Secret: OPENAI_API_KEY (shared with voice-route).
import { CORS_HEADERS, json, serveJson } from "../_shared/http.ts";
import { requireUser } from "../_shared/supabase.ts";

type SpeakInput = { text?: string; voice?: string };

const MODEL = "gpt-4o-mini-tts";
const ENDPOINT = "https://api.openai.com/v1/audio/speech";
const INSTRUCTIONS =
  "You are the voice of a calm, friendly British gardening assistant. " +
  "Speak naturally and warmly at a measured pace, like a helpful colleague " +
  "on the phone. No theatrics, no radio-announcer tone.";

Deno.serve(
  serveJson(async (request) => {
    await requireUser(request);
    const apiKey = Deno.env.get("OPENAI_API_KEY");
    if (!apiKey) return json({ error: "OPENAI_API_KEY is not set for this project" }, 503);

    let input: SpeakInput;
    try {
      input = (await request.json()) as SpeakInput;
    } catch {
      return json({ error: "Invalid body" }, 400);
    }

    const text = typeof input.text === "string" ? input.text.trim().slice(0, 600) : "";
    if (!text) return json({ error: "Text is required" }, 400);
    const voice = typeof input.voice === "string" ? input.voice.slice(0, 20) : "fable";

    const response = await fetch(ENDPOINT, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: MODEL,
        voice,
        input: text,
        instructions: INSTRUCTIONS,
        response_format: "mp3",
      }),
    });

    if (!response.ok) {
      const detail = (await response.text()).slice(0, 300);
      console.error("OpenAI TTS failed", response.status, detail);
      return json({ error: `OpenAI TTS HTTP ${response.status}` }, 502);
    }

    return new Response(response.body, {
      status: 200,
      headers: {
        ...CORS_HEADERS,
        "Content-Type": "audio/mpeg",
        "Cache-Control": "no-store",
      },
    });
  }),
);
