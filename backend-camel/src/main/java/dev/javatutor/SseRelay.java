package dev.javatutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Turns an OpenAI streaming response (SSE) into this API's SSE events, applying the answer policy on the fly.
 * platform-http copies this stream to the client, so every read() hands over one event as soon as it exists.
 */
final class SseRelay extends InputStream {

    record Meta(String traceId) {}
    record Token(String text) {}
    record Done(String traceId, long firstTokenMs, long totalMs, Integer inputTokens, Integer outputTokens) {}
    record Failure(String error, String traceId) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BufferedReader upstream;
    private final AnswerStream answer = new AnswerStream();
    private final String traceId;
    private final long start;
    private Integer inputTokens;
    private Integer outputTokens;
    private byte[] pending;
    private int pos;
    private boolean finished;

    SseRelay(InputStream upstream, String traceId, long start) {
        this.upstream = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
        this.traceId = traceId;
        this.start = start;
        this.pending = event("meta", new Meta(traceId));
    }

    @Override
    public int read(byte[] b, int off, int len) {
        while (pos == pending.length) {
            if (finished) return -1;
            pending = next();
            pos = 0;
        }
        int n = Math.min(len, pending.length - pos); // never wait to fill the caller's buffer
        System.arraycopy(pending, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int available() {
        return pending.length - pos;
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }

    /** Reads upstream until there is something to send: a token, the final events, or an error. */
    private byte[] next() {
        try {
            String line;
            while ((line = upstream.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) break;
                JsonNode chunk = JSON.readTree(data);
                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) { // last chunk, thanks to stream_options.include_usage
                    inputTokens = usage.path("prompt_tokens").asInt();
                    outputTokens = usage.path("completion_tokens").asInt();
                }
                String text = answer.accept(chunk.at("/choices/0/delta/content").asText(""));
                if (!text.isEmpty()) return event("token", new Token(text));
            }
            finished = true;
            String rest = answer.finish();
            var done = new Done(traceId, answer.firstChunkAt() == 0 ? -1 : (answer.firstChunkAt() - start) / 1_000_000,
                    sinceMs(), inputTokens, outputTokens);
            Log.infof("ask done traceId=%s firstTokenMs=%d totalMs=%d inputTokens=%s outputTokens=%s",
                    traceId, done.firstTokenMs(), done.totalMs(), inputTokens, outputTokens);
            byte[] last = event("done", done);
            return rest.isEmpty() ? last : concat(event("token", new Token(rest)), last);
        } catch (AnswerStream.Rejected e) {
            Log.infof("ask rejected traceId=%s stage=output totalMs=%d", traceId, sinceMs());
            return fail(e.getMessage());
        } catch (IOException e) {
            Log.errorf("ask failed traceId=%s totalMs=%d: %s", traceId, sinceMs(), e);
            return fail("The model stream was interrupted. Try again later.");
        }
    }

    private byte[] fail(String reason) {
        finished = true;
        try { upstream.close(); } catch (IOException ignored) { /* already failing */ }
        return event("error", new Failure(reason, traceId));
    }

    private long sinceMs() {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static byte[] event(String name, Object data) {
        try {
            return ("event: " + name + "\ndata: " + JSON.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
