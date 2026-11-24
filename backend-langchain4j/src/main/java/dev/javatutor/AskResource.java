package dev.javatutor;

import dev.langchain4j.guardrail.GuardrailException;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.quarkiverse.langchain4j.runtime.aiservice.ChatEvent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

/**
 * POST /api/ask {"question": "..."} streams Server-Sent Events:
 * meta {traceId}, token {text}*, then done {traceId, firstTokenMs, totalMs, inputTokens, outputTokens} or error {error, traceId}.
 * Questions rejected by the input policy get 422 {error, traceId} before any streaming starts.
 */
@Path("/api/ask")
public class AskResource {

    public record Question(String question) {}
    public record Meta(String traceId) {}
    public record Token(String text) {}
    public record Done(String traceId, long firstTokenMs, long totalMs, Integer inputTokens, Integer outputTokens) {}
    public record Failure(String error, String traceId) {}

    @Inject
    JavaTutor tutor;

    @POST
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> ask(Question body, @Context Sse sse) {
        long start = System.nanoTime();
        String traceId = Span.current().getSpanContext().getTraceId();
        String question = body == null ? null : body.question();
        Policy.checkQuestion(question).ifPresent(reason -> {
            Log.infof("ask rejected traceId=%s stage=input totalMs=%d", traceId, sinceMs(start));
            throw new WebApplicationException(Response.status(422).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new Failure(reason, traceId)).build());
        });

        var answer = new AnswerStream();
        Multi<OutboundSseEvent> answerEvents = tutor.ask(question).onItem().transformToMultiAndConcatenate(event -> switch (event) {
            case ChatEvent.PartialResponseEvent partial -> tokens(sse, answer.accept(partial.getChunk()));
            case ChatEvent.ChatCompletedEvent completed -> {
                String rest = answer.finish();
                TokenUsage usage = completed.getChatResponse().tokenUsage();
                var done = new Done(traceId, answer.firstChunkAt() == 0 ? -1 : (answer.firstChunkAt() - start) / 1_000_000,
                        sinceMs(start), usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount());
                Log.infof("ask done traceId=%s firstTokenMs=%d totalMs=%d inputTokens=%s outputTokens=%s",
                        traceId, done.firstTokenMs(), done.totalMs(), done.inputTokens(), done.outputTokens());
                yield Multi.createBy().concatenating().streams(tokens(sse, rest), Multi.createFrom().item(event(sse, "done", done)));
            }
            default -> Multi.createFrom().empty();
        });

        return Multi.createBy().concatenating()
                .streams(Multi.createFrom().item(event(sse, "meta", new Meta(traceId))), answerEvents)
                .onFailure().recoverWithItem(e -> event(sse, "error", new Failure(describe(e, traceId, start), traceId)));
    }

    private static String describe(Throwable e, String traceId, long start) {
        String reason = switch (e) {
            case AnswerStream.Rejected r -> r.getMessage();
            case GuardrailException g -> "The question did not pass the input policy.";
            default -> null;
        };
        if (reason != null) {
            Log.infof("ask rejected traceId=%s stage=output totalMs=%d", traceId, sinceMs(start));
            return reason;
        }
        Log.errorf("ask failed traceId=%s totalMs=%d: %s", traceId, sinceMs(start), e); // provider error only, never the question
        return "The model service is unavailable. Try again later.";
    }

    private static Multi<OutboundSseEvent> tokens(Sse sse, String text) {
        return text.isEmpty() ? Multi.createFrom().empty() : Multi.createFrom().item(event(sse, "token", new Token(text)));
    }

    private static OutboundSseEvent event(Sse sse, String name, Object data) {
        return sse.newEventBuilder().name(name).mediaType(MediaType.APPLICATION_JSON_TYPE).data(data).build();
    }

    private static long sinceMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
