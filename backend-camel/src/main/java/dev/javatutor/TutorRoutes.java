package dev.javatutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.http.base.HttpOperationFailedException;

/**
 * POST /api/ask {"question": "..."} streams Server-Sent Events:
 * meta {traceId}, token {text}*, then done {traceId, firstTokenMs, totalMs, inputTokens, outputTokens} or error {error, traceId}.
 * Same contract and guardrails as the LangChain4j backend, built from plain Camel components.
 */
public class TutorRoutes extends RouteBuilder {

    public record Question(String question) {}

    private static final String START = "start";
    private static final String TRACE_ID = "traceId";
    private static final String QUESTION = "question";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String systemPrompt = resource("system-prompt.txt");

    @Override
    public void configure() {
        String model = getContext().resolvePropertyPlaceholders("{{openai.model}}");
        String reasoningEffort = getContext().resolvePropertyPlaceholders("{{openai.reasoning-effort}}");
        // No REST binding: the success response is a stream, not a JSON document.
        restConfiguration().component("platform-http");

        onException(AnswerStream.Rejected.class).handled(true)
                .log("ask rejected traceId=${exchangeProperty.traceId} stage=input")
                .process(e -> jsonError(e, 422, e.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class).getMessage()));
        onException(HttpOperationFailedException.class).handled(true)
                .log("ask failed traceId=${exchangeProperty.traceId}: model call returned HTTP ${exception.statusCode}") // never the payload
                .process(e -> jsonError(e, 502, "The model service is unavailable. Try again later."));

        rest("/api").post("/ask").consumes("application/json").produces("text/event-stream").to("direct:ask");

        from("direct:ask").routeId("ask")
                // input guardrail: rejected questions never leave this JVM
                .process(e -> {
                    e.setProperty(START, System.nanoTime());
                    e.setProperty(TRACE_ID, traceId(e));
                    String question = parseQuestion(e.getMessage().getBody(String.class));
                    Policy.checkQuestion(question).ifPresent(reason -> { throw new AnswerStream.Rejected(reason); });
                    e.setProperty(QUESTION, question);
                })
                .removeHeaders("*") // don't forward the browser's headers (cookies, origin, ...) to the provider
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setHeader("Accept", constant("text/event-stream"))
                // uncompressed, so every chunk can be relayed immediately (and HttpClient's brotli decoder is never used)
                .setHeader("Accept-Encoding", constant("identity"))
                .setHeader("Authorization", simple("Bearer {{openai.api-key}}"))
                .process(e -> e.getMessage().setBody(JSON.writeValueAsString(Map.of(
                        "model", model,
                        "reasoning_effort", reasoningEffort,
                        "stream", true,
                        "stream_options", Map.of("include_usage", true),
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", e.getProperty(QUESTION, String.class)))))))
                // disableStreamCache + camel.main.stream-caching-enabled=false: hand over the live response stream, unread
                .to("{{openai.base-url}}/chat/completions?disableStreamCache=true&connectTimeout=10000&soTimeout=90000")
                .process(e -> {
                    var m = e.getMessage();
                    InputStream upstream = m.getBody(InputStream.class);
                    m.removeHeaders("*"); // don't leak the provider's response headers to the browser
                    m.setHeader(Exchange.CONTENT_TYPE, "text/event-stream");
                    m.setHeader("Cache-Control", "no-cache");
                    // output guardrail + timings are applied inside the relay, chunk by chunk
                    m.setBody(new SseRelay(upstream, e.getProperty(TRACE_ID, String.class), e.getProperty(START, Long.class)));
                });
    }

    /** The W3C trace id of this request: the OpenTelemetry span if there is one, else the caller's traceparent. */
    private static String traceId(Exchange e) {
        var ctx = Span.current().getSpanContext();
        if (ctx.isValid()) return ctx.getTraceId();
        String parent = e.getMessage().getHeader("traceparent", String.class);
        return parent != null && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") ? parent.substring(3, 35) : "";
    }

    private static String parseQuestion(String body) {
        try {
            Question q = JSON.readValue(body == null ? "" : body, Question.class);
            return q == null ? null : q.question();
        } catch (IOException ex) {
            throw new AnswerStream.Rejected("Request body must be JSON: {\"question\": \"...\"}");
        }
    }

    private static void jsonError(Exchange e, int status, String reason) throws IOException {
        var m = e.getMessage();
        m.removeHeaders("*");
        m.setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        m.setHeader(Exchange.CONTENT_TYPE, "application/json");
        m.setBody(JSON.writeValueAsString(new SseRelay.Failure(reason, e.getProperty(TRACE_ID, String.class))));
    }

    private static String resource(String name) {
        try (InputStream in = TutorRoutes.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
