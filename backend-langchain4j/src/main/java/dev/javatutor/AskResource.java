package dev.javatutor;

import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.guardrail.GuardrailException;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/api/ask")
public class AskResource {

    public record Question(String question) {}
    public record Answer(String answer) {}
    public record Rejection(String error) {}

    private static final String REFUSED = "REFUSED:";

    @Inject
    JavaTutor tutor;

    @POST
    public Response ask(Question body) {
        String question = body == null ? null : body.question();
        // Guardrails also run inside the AI service; checking here first gives a clean message for the common case.
        var violation = Policy.checkQuestion(question);
        if (violation.isPresent()) return reject(violation.get());
        try {
            String answer = tutor.ask(question).strip();
            if (answer.startsWith(REFUSED)) return reject(answer.substring(REFUSED.length()).strip());
            return Response.ok(new Answer(answer)).build();
        } catch (GuardrailException e) {
            return reject("The answer did not pass the Java-only policy. Please rephrase your question.");
        } catch (LangChain4jException e) {
            Log.errorf("Model call failed: %s", e.getMessage()); // provider error only, never the question
            return Response.status(502).entity(new Rejection("The model service is unavailable. Try again later.")).build();
        }
    }

    private static Response reject(String reason) {
        return Response.status(422).entity(new Rejection(reason)).build();
    }
}
