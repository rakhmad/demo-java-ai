package dev.javatutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.model.rest.RestBindingMode;

/**
 * POST /api/ask {"question": "..."} -> {"answer": "..."} | 422 {"error": "..."}
 * Same contract and guardrails as the LangChain4j backend, built from plain Camel components.
 */
public class TutorRoutes extends RouteBuilder {

    public record Question(String question) {}

    /** A guardrail rejected the exchange; mapped to HTTP 422. */
    static class PolicyViolation extends RuntimeException {
        PolicyViolation(String reason) { super(reason); }
    }

    private static final String MESSAGES = "messages";
    private static final String REFUSED = "REFUSED:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String systemPrompt = resource("system-prompt.txt");

    @Override
    public void configure() {
        String model = getContext().resolvePropertyPlaceholders("{{openai.model}}");
        restConfiguration().component("platform-http").bindingMode(RestBindingMode.json);

        onException(PolicyViolation.class).handled(true)
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(422))
                .setBody(e -> Map.of("error", e.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class).getMessage()))
                .to("direct:jsonError");
        onException(HttpOperationFailedException.class).handled(true)
                .log("Model call failed: HTTP ${exception.statusCode}") // status only: never log the payload
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(502))
                .setBody(constant(Map.of("error", "The model service is unavailable. Try again later.")))
                .to("direct:jsonError");
        // REST binding does not marshal bodies set by onException, so do it here.
        from("direct:jsonError").setHeader(Exchange.CONTENT_TYPE, constant("application/json")).marshal().json();

        rest("/api").post("/ask").type(Question.class).to("direct:ask");

        from("direct:ask").routeId("ask")
                // input guardrail: rejected questions never leave this JVM
                .process(e -> {
                    Question q = e.getMessage().getBody(Question.class);
                    String question = q == null ? null : q.question();
                    Policy.checkQuestion(question).ifPresent(reason -> { throw new PolicyViolation(reason); });
                    e.setProperty(MESSAGES, new ArrayList<>(List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", question))));
                })
                .to("direct:callModel")
                // output guardrail: one rewrite attempt if the model emitted non-Java code
                .process(e -> checkAnswer(e, true))
                .filter(exchangeProperty("retry"))
                    .to("direct:callModel")
                    .process(e -> checkAnswer(e, false))
                .end()
                .removeHeaders("*") // don't leak the model provider's response headers to the browser
                .setBody(e -> Map.of("answer", e.getMessage().getBody(String.class)));

        // Calls any OpenAI-compatible /chat/completions endpoint with the conversation in the MESSAGES property.
        from("direct:callModel").routeId("call-model")
                .removeHeaders("*") // don't forward the browser's headers (cookies, origin, ...) to the provider
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setHeader("Authorization", simple("Bearer {{openai.api-key}}"))
                // HttpClient 5 otherwise offers brotli, whose native decoder fails to load in Quarkus dev mode
                .setHeader("Accept-Encoding", constant("gzip"))
                .process(e -> e.getMessage().setBody(JSON.writeValueAsString(Map.of(
                        "model", model,
                        "messages", e.getProperty(MESSAGES)))))
                .to("{{openai.base-url}}/chat/completions?connectTimeout=10000&soTimeout=90000")
                .process(e -> {
                    JsonNode reply = JSON.readTree(e.getMessage().getBody(InputStream.class));
                    e.getMessage().setBody(reply.at("/choices/0/message/content").asText("").strip());
                });
    }

    @SuppressWarnings("unchecked")
    private static void checkAnswer(Exchange e, boolean mayRetry) {
        String answer = e.getMessage().getBody(String.class);
        e.setProperty("retry", false);
        if (answer.startsWith(REFUSED)) throw new PolicyViolation(answer.substring(REFUSED.length()).strip());
        var badFence = Policy.checkAnswer(answer);
        if (badFence.isEmpty()) return;
        if (!mayRetry) throw new PolicyViolation("The answer did not pass the Java-only policy. Please rephrase your question.");
        List<Map<String, String>> messages = e.getProperty(MESSAGES, List.class);
        messages.add(Map.of("role", "assistant", "content", answer));
        messages.add(Map.of("role", "user", "content", "Rewrite your answer. Code blocks may only be java, mermaid, svg or console. Do not include any "
                + badFence.get() + " code."));
        e.setProperty("retry", true);
    }

    private static String resource(String name) {
        try (InputStream in = TutorRoutes.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
