package dev.javatutor;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import jakarta.enterprise.context.ApplicationScoped;

public final class Guardrails {

    private Guardrails() {}

    /** Runs before the model is called: rejected questions never leave this JVM. */
    @ApplicationScoped
    public static class QuestionGuard implements InputGuardrail {
        @Override
        public InputGuardrailResult validate(UserMessage message) {
            return Policy.checkQuestion(message.singleText()).map(this::fatal).orElseGet(this::success);
        }
    }

    // The answer policy is applied per chunk by AnswerStream, not as an @OutputGuardrails annotation:
    // annotation output guardrails need the complete response, which would buffer the stream.
}
