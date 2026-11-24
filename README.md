# Java Tutor

An AI assistant for Java programming questions. Two interchangeable Java 25 backends call an
OpenAI-compatible model service (OpenAI `gpt-5.5` by default) and enforce guardrails. A small web frontend
renders the answers with syntax-highlighted Java, Mermaid diagrams and SVG illustrations.

| App | Stack | Port |
|-----|-------|------|
| [`backend-langchain4j`](backend-langchain4j) | Quarkus 3.40 + quarkus-langchain4j (AI service, input/output guardrails) | 8080 |
| [`backend-camel`](backend-camel) | Quarkus 3.40 + Apache Camel (REST DSL, platform-http, http component) | 8081 |
| [`frontend`](frontend) | Single static page: marked, highlight.js, Mermaid, DOMPurify (from CDN) | 3000 |

## Quick start

Requirements: macOS/Linux, `curl`, `python3` (serves the frontend). The setup script installs JDK 25
(and Maven if missing) through [sdkman](https://sdkman.io).

```bash
./scripts/setup-dev.sh     # installs JDK 25 (project-local, not your global default), creates .env
# edit .env and set OPENAI_API_KEY
./scripts/run.sh           # starts all three apps; or: ./scripts/run.sh langchain4j|camel|frontend
open http://localhost:3000
```

Pick the backend from the dropdown at the top of the page.

### Configuration (`.env`)

| Variable | Default | Purpose |
|----------|---------|---------|
| `OPENAI_API_KEY` | (required) | API key for the model service |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Any OpenAI-compatible endpoint |
| `OPENAI_MODEL` | `gpt-5.5` | Chat model name |
| `OPENAI_REASONING_EFFORT` | `low` | `low`, `medium` or `high`. Lower is faster and cheaper |
| `TRACES_EXPORTER` | `none` | `none` keeps trace ids and logs only; `cdi` exports spans over OTLP to `localhost:4317` |

`.env` is gitignored. Never commit a real key.

## API

Both backends expose the same contract. Answers are streamed as [Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html):

```http
POST /api/ask
Content-Type: application/json
traceparent: 00-<trace-id>-<span-id>-01      (optional, W3C Trace Context)

{"question": "How does a HashMap handle collisions?"}
```

| Status | Body |
|--------|------|
| 200 | `text/event-stream`, see the events below |
| 422 | `{"error": "<reason>", "traceId": "..."}`: the question was rejected before reaching the model |
| 502 | `{"error": "...", "traceId": "..."}`: the model service failed (Camel backend; the LangChain4j backend reports this as an `error` event) |

| Event | Data | When |
|-------|------|------|
| `meta` | `{"traceId"}` | first |
| `token` | `{"text"}` | one per chunk of the Markdown answer |
| `done` | `{"traceId", "firstTokenMs", "totalMs", "inputTokens", "outputTokens"}` | the answer is complete and passed the policy |
| `error` | `{"error", "traceId"}` | refusal, policy violation or model failure. Discard the tokens received so far |

```bash
curl -N localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"question":"Explain virtual threads with a diagram"}'
```

## Performance

- **Streaming**: the first words show up after about 1 to 3 s instead of after the whole answer.
  Illustrations render once the answer is complete.
- **Low reasoning effort** (`OPENAI_REASONING_EFFORT=low`): concept explanations rarely need deep reasoning.
- The frontend shows timings under each answer: time to first token and total server time (both measured by the
  backend), the round trip seen by the browser, and token usage.

## Traceability

- The browser starts a W3C trace (`traceparent` header). Both backends continue it with OpenTelemetry. Without the
  header, the backend starts a new trace.
- The trace id appears in the UI (with a copy button), in every event, and in every backend log line
  (`traceId=...`, plus one `ask done`, `ask rejected` or `ask failed` summary per request). Questions and answers are never logged.
- To see spans, including the LangChain4j model call and the Camel route steps: set `TRACES_EXPORTER=cdi` in `.env`,
  run `./scripts/run.sh jaeger` (Docker or Podman), restart the backends, then open http://localhost:16686.

## Guardrails

All guardrails are enforced **on the backend**, so calling the API directly cannot skip them.
The frontend repeats the personal-data and English checks only to warn while you type.

1. **Input checks, before the model is called** (`Policy.checkQuestion`): rejected questions never leave the server.
   - Length limit (4000 characters)
   - Personal data: emails, phone numbers, SSNs, Luhn-valid card numbers, phrases like "my name is ..."
   - English only: rejects non-Latin scripts and text where non-English stop words outnumber English ones
   - Java only: rejects strong markers of other languages (Python `def`, `console.log`, `#include`, Rust `fn`, Go `:=`, ...)
2. **Output check, while the answer streams** (`AnswerStream`, using `Policy.checkAnswer`): each complete line is checked as it
   arrives. A code block in any language other than `java | mermaid | svg | console | text` stops the stream with an
   `error` event, and the frontend withdraws the partial answer. A reply that may be a `REFUSED:` refusal is held
   back until that is certain, so refusals never reach the user as partial text.
3. **System prompt** ([`system-prompt.txt`](backend-langchain4j/src/main/resources/system-prompt.txt)): Java and
   computer-science topics only, Java-only code, English only. The model answers `REFUSED: <reason>`, which the
   backends return as a 422.

Where they run:

| | LangChain4j backend | Camel backend |
|-|---------------------|---------------|
| Input | `@InputGuardrails(QuestionGuard)` plus a check in `AskResource` | first processor in the `ask` route |
| Output | `AnswerStream` applied to the `Multi<ChatEvent>` stream | `AnswerStream` inside `SseRelay` |

The output check is no longer a LangChain4j `@OutputGuardrails` annotation: annotated output guardrails need the complete
response, which would buffer the stream. The trade-off is that a violating answer is rejected instead of rewritten.

### Privacy

- Stateless: no chat memory, so nothing is kept between requests.
- Questions and answers are never logged. Only provider error status or messages are logged.
- The Camel backend strips incoming browser headers before calling the provider, and strips provider headers before replying.

### Rendering safety

Model output is treated as untrusted. The frontend sanitizes the HTML and SVG with DOMPurify (scripts, event handlers,
`<style>` and `foreignObject` are stripped). Mermaid runs with `securityLevel: "strict"`.

## Development

```bash
mvn -f backend-langchain4j/pom.xml test    # policy and streaming-guard tests
```

`Policy.java`, `AnswerStream.java` and `system-prompt.txt` are duplicated in both backends on purpose, so each app stays self-contained.
Keep the two copies in sync.

Each backend starts in Quarkus dev mode with live reload. Debug ports are 5005 (LangChain4j) and 5006 (Camel).

## License

[Apache License 2.0](LICENSE)
