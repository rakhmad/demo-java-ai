package dev.javatutor;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
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

    /** Runs after the model answers: non-Java code makes the model try again (up to the configured max retries). */
    @ApplicationScoped
    public static class JavaOnlyAnswerGuard implements OutputGuardrail {
        @Override
        public OutputGuardrailResult validate(AiMessage message) {
            return Policy.checkAnswer(message.text())
                    .map(lang -> reprompt("Answer contained a '" + lang + "' code block.",
                            "Rewrite your answer. Code blocks may only be java, mermaid, svg or console. Do not include any "
                                    + lang + " code."))
                    .orElseGet(this::success);
        }
    }
}
