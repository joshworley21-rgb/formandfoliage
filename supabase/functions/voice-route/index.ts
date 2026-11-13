// Cloud fallback for the phone's voice assistant.
//
// The offline Qwen classifier flags a spoken turn as `requires_cloud` when the
// answer needs live data (material prices, supplier rates, a quote). The app
// then posts the transcript here instead of shipping an OpenAI key inside the
// APK. This function holds the key, runs the two tools locally against an
// illustrative rate card, and returns one spoken sentence.
//
// Secret: OPENAI_API_KEY must be set for the project, e.g.
//   supabase secrets set OPENAI_API_KEY=sk-...
// Deployed with verify_jwt = true, so only a signed-in app can spend tokens.
import { json, serveJson } from "../_shared/http.ts";
import { requireUser } from "../_shared/supabase.ts";

type SpokenInput = {
  query?: string;
  client_or_topic?: string;
};

type ChatMessage = {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
};

type ToolCall = {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
};

const MODEL = "gpt-4o-mini";
const OPENAI_URL = "https://api.openai.com/v1/chat/completions";
const LABOUR_RATE = 35.0;
const VAT_RATE = 0.2;

const SYSTEM_PROMPT = `You are the phone assistant for JW Garden Services, a UK gardening
business. Answer spoken questions in at most 45 words, in GBP, with no lists,
markdown or emoji: your reply is read aloud by a speech engine.

Use fetch_live_pricing whenever a material price is needed and
calculate_commercial_quote whenever a quote, estimate or job total is needed.
Never invent a price that a tool did not return. If a tool marks its answer as
an estimate, say so plainly ("that's an estimate"). Ask one short clarifying
question if the request lacks the material or the scope.`;

/** Illustrative rate card; swap for a live supplier API later. */
const RATE_CARD: Record<string, { price: number; unit: string }> = {
  turf: { price: 7.5, unit: "m2" },
  grass: { price: 7.5, unit: "m2" },
  bark: { price: 6.0, unit: "bag" },
  mulch: { price: 6.0, unit: "bag" },
  paving: { price: 45.0, unit: "m2" },
  sleeper: { price: 28.0, unit: "each" },
  soil: { price: 5.0, unit: "bag" },
  labour: { price: LABOUR_RATE, unit: "hour" },
};

const TOOLS = [
  {
    type: "function",
    function: {
      name: "fetch_live_pricing",
      description: "Look up the current price of a garden material.",
      parameters: {
        type: "object",
        properties: {
          material_type: {
            type: "string",
            description: "Material such as turf, bark, paving, sleepers or soil",
          },
        },
        required: ["material_type"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "calculate_commercial_quote",
      description: "Calculate a commercial quote for a client's scope of work.",
      parameters: {
        type: "object",
        properties: {
          client: { type: "string", description: "Client name or site" },
          scope: {
            type: "string",
            description: "Work requested, e.g. turf 40 square metres or hedge trimming",
          },
        },
        required: ["client", "scope"],
      },
    },
  },
];

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

function text(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function argument(raw: string, key: string): string {
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return typeof parsed[key] === "string" ? (parsed[key] as string) : "";
  } catch {
    return "";
  }
}

function fetchLivePricing(argumentsJson: string): string {
  const material = argument(argumentsJson, "material_type").toLowerCase();
  const key = Object.keys(RATE_CARD).find((candidate) => material.includes(candidate));
  const match = key ? RATE_CARD[key] : { price: 12.0, unit: "unit" };
  return JSON.stringify({
    material_type: material || "unspecified",
    canonical_material: key ?? "unlisted",
    unit_price_gbp: match.price,
    unit: match.unit,
    currency: "GBP",
    source: "local rate card",
    is_estimate: true,
    note: "Illustrative rate; confirm with the supplier before quoting.",
  });
}

function calculateQuote(argumentsJson: string): string {
  const client = argument(argumentsJson, "client");
  const scope = argument(argumentsJson, "scope");
  const lower = scope.toLowerCase();
  const hours = lower.includes("mow")
    ? 1.5
    : lower.includes("hedge")
      ? 3.0
      : lower.includes("design") || lower.includes("landscap")
        ? 16.0
        : lower.includes("clean")
          ? 2.0
          : 4.0;
  const materials = lower.includes("turf")
    ? 320.0
    : lower.includes("bark") || lower.includes("mulch")
      ? 140.0
      : lower.includes("paving")
        ? 480.0
        : 90.0;
  const labour = hours * LABOUR_RATE;
  const subtotal = labour + materials;
  const vat = round(subtotal * VAT_RATE);
  return JSON.stringify({
    client: client || "unspecified",
    scope,
    currency: "GBP",
    labour_hours: hours,
    labour_rate_gbp: LABOUR_RATE,
    labour_total_gbp: round(labour),
    materials_estimate_gbp: materials,
    subtotal_gbp: round(subtotal),
    vat_rate_percent: 20,
    vat_gbp: vat,
    total_gbp: round(subtotal + vat),
    is_estimate: true,
    note: "Illustrative quote from the local rate card; confirm before sending.",
  });
}

function runTool(call: ToolCall): string {
  switch (call.function.name) {
    case "fetch_live_pricing":
      return fetchLivePricing(call.function.arguments);
    case "calculate_commercial_quote":
      return calculateQuote(call.function.arguments);
    default:
      return JSON.stringify({ error: `Unknown tool ${call.function.name}` });
  }
}

async function chat(apiKey: string, messages: ChatMessage[]): Promise<ChatMessage> {
  const response = await fetch(OPENAI_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      messages,
      tools: TOOLS,
      tool_choice: "auto",
      temperature: 0.2,
    }),
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 300);
    throw new Error(`OpenAI HTTP ${response.status}: ${detail}`);
  }
  const parsed = (await response.json()) as { choices?: { message?: ChatMessage }[] };
  const message = parsed.choices?.[0]?.message;
  if (!message) throw new Error("OpenAI returned no choices");
  return message;
}

Deno.serve(
  serveJson(async (request) => {
    // Verifying the caller keeps the endpoint from being a free token faucet.
    await requireUser(request);
    const apiKey = Deno.env.get("OPENAI_API_KEY");
    if (!apiKey) return json({ error: "OPENAI_API_KEY is not set for this project" }, 503);

    const input = (await request.json()) as SpokenInput;
    const query = text(input.query, 500);
    const topic = text(input.client_or_topic, 120);
    if (!query) return json({ error: "A spoken query is required" }, 400);

    const messages: ChatMessage[] = [
      { role: "system", content: SYSTEM_PROMPT },
      {
        role: "user",
        content: `Customer or topic: ${topic || "not stated"}\nSpoken request: ${query}`,
      },
    ];

    const first = await chat(apiKey, messages);
    const calls = first.tool_calls ?? [];
    if (calls.length === 0) {
      return json({ answer: (first.content ?? "").trim() });
    }

    messages.push(first);
    for (const call of calls) {
      messages.push({
        role: "tool",
        content: runTool(call),
        tool_call_id: call.id,
      });
    }

    const second = await chat(apiKey, messages);
    return json({ answer: (second.content ?? "").trim() });
  }),
);
