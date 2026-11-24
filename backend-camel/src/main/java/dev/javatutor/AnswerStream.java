package dev.javatutor;

/**
 * Applies the answer policy to a streamed answer, chunk by chunk.
 * Holds output back while it could still be a "REFUSED:" reply, and aborts as soon as a complete line
 * opens a non-Java code block. One instance per request; not thread-safe.
 */
final class AnswerStream {

    /** The question or answer violates the policy; the message is safe to show to the user. */
    static final class Rejected extends RuntimeException {
        Rejected(String reason) { super(reason); }
    }

    private static final String REFUSED = "REFUSED:";

    private final StringBuilder text = new StringBuilder();
    private int sent;
    private long firstChunkAt;

    /** @return text that is safe to forward now (possibly empty). */
    String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        if (firstChunkAt == 0) firstChunkAt = System.nanoTime();
        text.append(chunk);
        // ponytail: re-scans the whole answer per chunk (O(n^2)); fine for answers of a few KB
        String head = text.toString().stripLeading();
        boolean maybeRefusal = head.length() < REFUSED.length() ? REFUSED.startsWith(head) : head.startsWith(REFUSED);
        if (maybeRefusal) return "";
        // only complete lines: "```ja" must not be judged before "va" arrives
        checkFences(text.substring(0, text.lastIndexOf("\n") + 1));
        return drain();
    }

    /** Call once the model is done. @return the remaining text to forward (possibly empty). */
    String finish() {
        String all = text.toString().strip();
        if (all.startsWith(REFUSED)) throw new Rejected(all.substring(REFUSED.length()).strip());
        if (all.isEmpty()) throw new Rejected("The model returned an empty answer. Please try again.");
        checkFences(all);
        return drain();
    }

    /** System.nanoTime() of the first non-empty chunk, 0 if none arrived. */
    long firstChunkAt() {
        return firstChunkAt;
    }

    private void checkFences(String complete) {
        Policy.checkAnswer(complete).ifPresent(lang -> {
            throw new Rejected("The answer contained non-Java code (" + lang + ") and was withdrawn. Please rephrase your question.");
        });
    }

    private String drain() {
        String out = text.substring(sent);
        sent = text.length();
        return out;
    }
}
