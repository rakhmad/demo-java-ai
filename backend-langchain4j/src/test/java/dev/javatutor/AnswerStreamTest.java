package dev.javatutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AnswerStreamTest {

    @Test
    void forwardsJavaAnswerAsItArrives() {
        var s = new AnswerStream();
        assertEquals("A record ", s.accept("A record "));
        assertEquals("is:\n```ja", s.accept("is:\n```ja")); // partial fence line is not judged yet
        assertEquals("va\nrecord P(int x) {}\n```\n", s.accept("va\nrecord P(int x) {}\n```\n"));
        assertEquals("", s.finish());
    }

    @Test
    void holdsBackAndRejectsRefusals() {
        var s = new AnswerStream();
        assertEquals("", s.accept("REF"));
        assertEquals("", s.accept("USED: I can only write Java code."));
        assertEquals("I can only write Java code.", assertThrows(AnswerStream.Rejected.class, s::finish).getMessage());
    }

    @Test
    void releasesTextThatOnlyLooksLikeARefusalAtFirst() {
        var s = new AnswerStream();
        assertEquals("", s.accept("RE"));
        assertEquals("REcursion is ...", s.accept("cursion is ..."));
    }

    @Test
    void abortsOnNonJavaFence() {
        var s = new AnswerStream();
        s.accept("Here:\n");
        assertThrows(AnswerStream.Rejected.class, () -> s.accept("```python\n"));
    }
}
