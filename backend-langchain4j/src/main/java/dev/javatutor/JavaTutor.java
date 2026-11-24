package dev.javatutor;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoChatMemoryProviderSupplier;
import io.quarkiverse.langchain4j.runtime.aiservice.ChatEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

// Stateless on purpose: no chat memory means nothing the user typed is kept between requests.
@RegisterAiService(chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface JavaTutor {

    /** Streams partial answers, then a ChatCompletedEvent carrying token usage. */
    @SystemMessage(fromResource = "system-prompt.txt")
    @InputGuardrails(Guardrails.QuestionGuard.class)
    Multi<ChatEvent> ask(String question);
}
