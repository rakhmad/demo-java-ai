package dev.javatutor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic guardrail rules, cheap enough to run before every model call.
 * The system prompt asks the model to enforce the same rules; this class is the part that cannot be talked out of it.
 */
public final class Policy {

    public static final int MAX_QUESTION_LENGTH = 4000;

    /** Fence languages the model may emit; everything else counts as non-Java code. */
    public static final Set<String> ALLOWED_FENCES = Set.of("", "java", "mermaid", "svg", "console", "text", "plaintext");

    private Policy() {}

    // ---------- personal data ----------
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w.])\\+?\\d{1,3}[\\s.-]?\\(?\\d{2,4}\\)?[\\s.-]\\d{3,4}[\\s.-]?\\d{3,4}(?![\\w.])");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern SELF_IDENTIFYING = Pattern.compile(
            "\\b(my (full )?name is|my (home )?address is|i live at|my (phone|mobile|email|passport|ssn|id) (number )?is)\\b",
            Pattern.CASE_INSENSITIVE);

    // ---------- non-Java code (strong markers only; the model handles subtle cases) ----------
    private static final List<Pattern> FOREIGN_CODE = List.of(
            Pattern.compile("^\\s*def \\w+\\s*\\(.*\\)\\s*(->.*)?:\\s*$", Pattern.MULTILINE), // Python
            Pattern.compile("^\\s*(from [\\w.]+ )?import \\w+( as \\w+)?(, ?\\w+)*\\s*$", Pattern.MULTILINE), // Python import (Java has dots and ';')
            Pattern.compile("\\bconsole\\.log\\s*\\("),                                         // JS
            Pattern.compile("\\bfunction\\s+\\w+\\s*\\("),                                      // JS / PHP
            Pattern.compile("^\\s*(const|let)\\s+\\w+\\s*=", Pattern.MULTILINE),                // JS
            Pattern.compile("=>\\s*\\{"),                                                       // JS / C# arrow (Java uses ->)
            Pattern.compile("^\\s*#include\\s*<", Pattern.MULTILINE),                           // C / C++
            Pattern.compile("\\bstd::|\\bcout\\s*<<"),                                          // C++
            Pattern.compile("^\\s*package main\\s*$", Pattern.MULTILINE),                       // Go
            Pattern.compile("\\bfunc\\s+\\w+\\s*\\(|\\w\\s*:=\\s*"),                            // Go
            Pattern.compile("\\bfn\\s+\\w+\\s*\\(|\\blet mut\\b|\\bprintln!\\s*\\("),           // Rust
            Pattern.compile("<\\?php|\\$\\w+\\s*=\\s*[^=]"),                                    // PHP
            Pattern.compile("\\bConsole\\.Write(Line)?\\s*\\(|^\\s*using System;", Pattern.MULTILINE), // C#
            Pattern.compile("^\\s*(fun|val)\\s+\\w+", Pattern.MULTILINE));                      // Kotlin / Scala

    // ---------- English heuristic ----------
    private static final Set<String> ENGLISH = Set.of(
            "the", "is", "are", "what", "how", "why", "when", "which", "does", "do", "can", "explain", "and", "of",
            "to", "in", "a", "an", "with", "for", "between", "difference", "example", "show", "me", "use", "it", "this");
    private static final Set<String> NON_ENGLISH = Set.of(
            // Indonesian / Malay
            "apa", "bagaimana", "yang", "dengan", "adalah", "jelaskan", "dan", "untuk", "itu", "ini", "cara", "kenapa", "tolong", "contoh",
            // Spanish / Portuguese
            "qué", "que", "como", "cómo", "por", "para", "una", "los", "las", "el", "es", "explica", "diferencia", "não", "você",
            // French
            "le", "la", "les", "des", "est", "quoi", "pourquoi", "comment", "une", "avec", "entre",
            // German / Dutch
            "der", "die", "das", "und", "ist", "wie", "warum", "nicht", "ein", "eine", "het", "een", "wat", "hoe",
            // Italian
            "il", "che", "perché", "spiega", "differenza");
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    /** @return a user-facing reason if the question must be rejected, empty otherwise. */
    public static Optional<String> checkQuestion(String q) {
        if (q == null || q.isBlank()) return Optional.of("Question must not be empty.");
        if (q.length() > MAX_QUESTION_LENGTH) return Optional.of("Question is too long (max " + MAX_QUESTION_LENGTH + " characters).");
        if (containsPersonalData(q)) return Optional.of("Please remove personal data (emails, phone numbers, ID/card numbers, names, addresses).");
        if (!looksEnglish(q)) return Optional.of("Only English is supported.");
        if (FOREIGN_CODE.stream().anyMatch(p -> p.matcher(q).find())) return Optional.of("Only Java code is supported.");
        return Optional.empty();
    }

    /** @return the first fenced code block language that is not allowed, empty if the answer is fine. */
    public static Optional<String> checkAnswer(String answer) {
        Matcher m = Pattern.compile("^\\s*```\\s*([\\w+#-]*)", Pattern.MULTILINE).matcher(answer);
        boolean opening = true; // fences alternate open/close; only opening fences carry a language
        while (m.find()) {
            String lang = m.group(1).toLowerCase();
            if (opening && !ALLOWED_FENCES.contains(lang)) return Optional.of(lang);
            opening = !opening;
        }
        return Optional.empty();
    }

    static boolean containsPersonalData(String s) {
        if (EMAIL.matcher(s).find() || SSN.matcher(s).find() || PHONE.matcher(s).find() || SELF_IDENTIFYING.matcher(s).find()) return true;
        Matcher card = CARD.matcher(s);
        while (card.find()) if (luhn(card.group().replaceAll("[ -]", ""))) return true;
        return false;
    }

    // ponytail: stop-word vote, misses short non-English text with no stop words; the system prompt is the backstop.
    // Upgrade path: a language-detection library (e.g. Lingua) if false negatives matter.
    static boolean looksEnglish(String s) {
        String prose = s.replaceAll("(?s)```.*?```", " ").replaceAll("`[^`]*`", " "); // ignore code
        long letters = prose.codePoints().filter(Character::isLetter).count();
        long latin = prose.codePoints().filter(Character::isLetter)
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN).count();
        if (letters > 0 && latin < letters * 0.9) return false; // Cyrillic, CJK, Arabic, ...
        int en = 0, other = 0;
        Matcher w = WORD.matcher(prose.toLowerCase());
        while (w.find()) {
            String word = w.group();
            if (ENGLISH.contains(word)) en++;
            else if (NON_ENGLISH.contains(word)) other++;
        }
        return other <= en;
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1 && (d *= 2) > 9) d -= 9;
            sum += d;
        }
        return sum % 10 == 0;
    }
}
