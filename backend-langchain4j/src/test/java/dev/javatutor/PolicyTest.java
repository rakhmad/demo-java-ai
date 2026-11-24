package dev.javatutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyTest {

    @Test
    void acceptsJavaQuestions() {
        for (String q : new String[] {
                "What is the difference between HashMap and TreeMap?",
                "Explain virtual threads with an example",
                "Why does this fail?\n```java\nList<String> xs = List.of(\"a\");\nxs.add(\"b\");\nSystem.out.printf(\"%s%n\", xs);\n```",
                "How does binary search work? Show it with a diagram.",
                "import java.util.List;\nwhat does this import do?"}) {
            assertEquals(Optional.empty(), Policy.checkQuestion(q), q);
        }
    }

    @Test
    void rejectsPersonalData() {
        assertTrue(Policy.checkQuestion("My email is jane.doe@example.com, what is a record?").isPresent());
        assertTrue(Policy.checkQuestion("Call me at +62 812-3456-7890 about streams").isPresent());
        assertTrue(Policy.checkQuestion("Validate card 4111 1111 1111 1111 in Java").isPresent());
        assertTrue(Policy.checkQuestion("My name is John Smith, explain generics").isPresent());
    }

    @Test
    void rejectsNonEnglish() {
        assertTrue(Policy.checkQuestion("Apa itu interface dan bagaimana cara menggunakannya?").isPresent());
        assertTrue(Policy.checkQuestion("¿Qué es una clase abstracta en Java?").isPresent());
        assertTrue(Policy.checkQuestion("Что такое JVM?").isPresent());
    }

    @Test
    void rejectsOtherLanguagesCode() {
        assertTrue(Policy.checkQuestion("Fix this:\ndef add(a, b):\n    return a + b").isPresent());
        assertTrue(Policy.checkQuestion("Why is console.log(x) undefined?").isPresent());
        assertTrue(Policy.checkQuestion("#include <stdio.h>\nint main() {}").isPresent());
        assertTrue(Policy.checkQuestion("fn main() { println!(\"hi\"); }").isPresent());
    }

    @Test
    void answerFences() {
        assertEquals(Optional.empty(), Policy.checkAnswer("```java\nint x;\n```\ntext\n```mermaid\ngraph TD; A-->B\n```"));
        assertEquals(Optional.of("python"), Policy.checkAnswer("```java\nint x;\n```\n```python\nx = 1\n```"));
    }
}
