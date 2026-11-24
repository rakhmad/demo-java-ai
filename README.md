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

`.env` is gitignored. Never commit a real key.

## API

Both backends expose the same contract:

```http
POST /api/ask
Content-Type: application/json

{"question": "How does a HashMap handle collisions?"}
```

| Status | Body | Meaning |
|--------|------|---------|
| 200 | `{"answer": "<markdown>"}` | Markdown answer; may contain `java`, `mermaid`, `svg`, `console` code blocks |
| 422 | `{"error": "<reason>"}` | Rejected by a guardrail |
| 502 | `{"error": "..."}` | The model service failed |

```bash
curl -s localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"question":"Explain virtual threads with a diagram"}'
```

## Guardrails

All guardrails are enforced **on the backend**, so calling the API directly cannot skip them.
The frontend repeats the personal-data and English checks only to warn while you type.

1. **Input checks, before the model is called** (`Policy.checkQuestion`): rejected questions never leave the server.
   - Length limit (4000 characters)
   - Personal data: emails, phone numbers, SSNs, Luhn-valid card numbers, phrases like "my name is ..."
   - English only: rejects non-Latin scripts and text where non-English stop words outnumber English ones
   - Java only: rejects strong markers of other languages (Python `def`, `console.log`, `#include`, Rust `fn`, Go `:=`, ...)
2. **Output check, after the model answers** (`Policy.checkAnswer`): a code block in any language other than
   `java | mermaid | svg | console | text` makes the model rewrite its answer. If the rewrite also fails, the request is rejected.
3. **System prompt** ([`system-prompt.txt`](backend-langchain4j/src/main/resources/system-prompt.txt)): Java and
   computer-science topics only, Java-only code, English only. The model answers `REFUSED: <reason>`, which the
   backends return as a 422.

Where they run:

| | LangChain4j backend | Camel backend |
|-|---------------------|---------------|
| Input | `@InputGuardrails(QuestionGuard)` | first processor in the `ask` route |
| Output | `@OutputGuardrails(JavaOnlyAnswerGuard)`, reprompt up to 2 times | processor after `direct:callModel`, one rewrite |

### Privacy

- Stateless: no chat memory, so nothing is kept between requests.
- Questions and answers are never logged. Only provider error status or messages are logged.
- The Camel backend strips incoming browser headers before calling the provider, and strips provider headers before replying.

### Rendering safety

Model output is treated as untrusted. The frontend sanitizes the HTML and SVG with DOMPurify (scripts, event handlers,
`<style>` and `foreignObject` are stripped). Mermaid runs with `securityLevel: "strict"`.

## Development

```bash
mvn -f backend-langchain4j/pom.xml test    # guardrail policy tests
```

`Policy.java` and `system-prompt.txt` are duplicated in both backends on purpose, so each app stays self-contained.
Keep the two copies in sync.

Each backend starts in Quarkus dev mode with live reload. Debug ports are 5005 (LangChain4j) and 5006 (Camel).

## License

[Apache License 2.0](LICENSE)
