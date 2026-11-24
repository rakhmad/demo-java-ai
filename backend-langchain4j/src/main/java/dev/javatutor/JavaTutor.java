package dev.javatutor;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoChatMemoryProviderSupplier;
import jakarta.enterprise.context.ApplicationScoped;

// Stateless on purpose: no chat memory means nothing the user typed is kept between requests.
@RegisterAiService(chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface JavaTutor {

    @SystemMessage(fromResource = "system-prompt.txt")
    @InputGuardrails(Guardrails.QuestionGuard.class)
    @OutputGuardrails(Guardrails.JavaOnlyAnswerGuard.class)
    String ask(String question);
}
