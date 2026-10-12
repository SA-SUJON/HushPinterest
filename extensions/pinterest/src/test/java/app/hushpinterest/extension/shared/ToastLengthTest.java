/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android cuts a text toast from an app aimed at Android 12 or later at two lines, about 64
 * English characters on a Galaxy S22, and the rest is lost. Pinterest's own toast holds no more.
 * Every English text that can reach a toast is read out of the sources and held to that length,
 * its translations to 80. A text reaches a toast straight from the call, through a local the
 * call reads, through a parameter its callers fill, or through a String method's returns.
 */
public class ToastLengthTest {
    private static final int ENGLISH_LIMIT = 64;
    // Translations run longer than English. This catches the ones that run far longer.
    private static final int TRANSLATION_LIMIT = 80;
    // How far a text is followed from its toast: through locals, parameters and String methods.
    private static final int DEPTH = 4;

    private static final Pattern TOAST = Pattern.compile("\\bshowToast(?:Short|Long)?\\s*\\(");
    private static final Pattern L10N_CALL = Pattern.compile("\\bL10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");
    private static final Pattern DECLARATION = Pattern.compile("([\\w<>\\[\\].?]+)\\s+(\\w+)\\s*\\(");
    private static final Pattern NAME = Pattern.compile("(?<![\\w.])(?:(\\w+)\\s*\\.\\s*)?([A-Za-z_]\\w*)\\b(\\s*\\()?");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    private static final Pattern NUMBER = Pattern.compile("%(?:\\d+\\$)?d");
    private static final Pattern WORDS = Pattern.compile("%(?:\\d+\\$)?s");
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList("if", "for", "while", "switch", "catch",
            "synchronized", "return", "new", "throw", "case", "else", "try", "do", "super", "this", "null", "true", "false"));
    // L10n's and String's own methods, which hand a text through rather than make one.
    private static final Set<String> PASSED_THROUGH = new HashSet<>(Arrays.asList("L10n", "t", "f", "quantity",
            "isolate", "format", "valueOf", "toString", "equals", "length", "isEmpty", "trim"));

    @Test
    public void everyToastFitsTwoLines() throws IOException {
        List<String> tooLong = new ArrayList<>();
        for (Map.Entry<String, String> toast : new Scan(realSources()).toasts().entrySet()) {
            int length = shownLength(toast.getKey());
            if (length > ENGLISH_LIMIT || toast.getKey().indexOf('\n') >= 0) {
                tooLong.add(toast.getValue() + ": \"" + toast.getKey() + "\" is " + length + " characters");
            }
        }
        assertEquals("toasts past two lines, over " + ENGLISH_LIMIT + ":\n" + String.join("\n", tooLong), 0, tooLong.size());
    }

    @Test
    public void everyToastTranslationStaysShort() throws IOException {
        Map<String, String> toasts = new Scan(realSources()).toasts();
        List<String> tooLong = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nCatalogTest.readTable(language);
            for (Map.Entry<String, String> toast : toasts.entrySet()) {
                // L10nCatalogTest holds each table to the code's English, so a missing row fails there.
                String translated = table.get(toast.getKey());
                if (translated == null) continue;
                int length = shownLength(translated);
                if (length > TRANSLATION_LIMIT || translated.indexOf('\n') >= 0) {
                    tooLong.add(language + ": \"" + toast.getKey() + "\" (" + toast.getValue() + ") is " + length
                            + " characters: " + translated);
                }
            }
        }
        assertEquals("translated toasts over " + TRANSLATION_LIMIT + ":\n" + String.join("\n", tooLong), 0, tooLong.size());
    }

    @Test
    public void theScanFollowsTextsToTheirToasts() throws IOException {
        Map<String, String> toasts = new Scan(realSources()).toasts();
        // A scan that quietly found nothing would pass every other test here.
        assertTrue("found " + toasts.size(), toasts.size() > 100);
        // A local, a String method's return, a parameter its callers fill, and a method's return in another class.
        for (String text : new String[]{"%1$s: queued %2$d, skipped %3$d, unsupported %4$d, failed %5$d.",
                "Settings exported, but the app holding it blocked the check.",
                "Couldn't share this file. Check %s for the saved file.",
                "Pinterest only streams this video. There's no file to download.",
                "Download started, but history wasn't saved. Check %s."}) {
            assertTrue("not found: " + text, toasts.containsKey(text));
        }
    }

    /** The shapes the scan has to follow, put in front of it on purpose, and one it must pass over. */
    @Test
    public void theScanCanFollowEachShape() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("A.java", "class A {\n"
                + "  void direct() { Utils.showToastLong(L10n.t(\"Direct\" + \" text\")); }\n"
                + "  void local(boolean x) { String said = x ? L10n.t(\"Local\") : null; Utils.showToastShort(said); }\n"
                + "  void caller() { say(L10n.f(\"Passed %s\", \"x\")); }\n"
                + "  private void say(@Nullable String text) { Utils.showToastLong(text); }\n"
                + "  void helper() { Utils.showToastLong(message(true)); }\n"
                + "  static String message(boolean x) { if (x) return L10n.t(\"Helper\"); return B.other(); }\n"
                + "  // Utils.showToastLong(L10n.t(\"Commented out\"));\n"
                + "}\n");
        sources.put("B.java", "class B {\n"
                + "  static String other() { return L10n.t(\"Other class\"); }\n"
                + "  static String unrelated() { return L10n.t(\"Not a toast\"); }\n"
                + "}\n");
        assertEquals(new HashSet<>(Arrays.asList("Direct text", "Local", "Passed %s", "x", "Helper", "Other class")),
                new Scan(sources).toasts().keySet());
    }

    @Test
    public void placeholdersCountAsWhatTheyShow() {
        assertEquals("Saved 12.".length(), shownLength("Saved %d."));
        assertEquals("In 0123456789.".length(), shownLength("In %1$s."));
    }

    /** The length on screen, with a count as two digits and a name or folder as ten characters. */
    private static int shownLength(String text) {
        return WORDS.matcher(NUMBER.matcher(text).replaceAll("12")).replaceAll("0123456789").length();
    }

    private static Map<String, String> realSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        for (Path file : L10nCatalogTest.sources()) {
            sources.put(file.getFileName().toString(), new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        return sources;
    }

    /** A method found in a source: its name, parameter names, return type and body. */
    private static final class Declared {
        final String name;
        final String returns;
        final List<String> parameters;
        final int nameAt;
        final int open;
        final int close;

        Declared(String name, String returns, List<String> parameters, int nameAt, int open, int close) {
            this.name = name;
            this.returns = returns;
            this.parameters = parameters;
            this.nameAt = nameAt;
            this.open = open;
            this.close = close;
        }
    }

    private static final class Source {
        final String name;
        final String text;
        final byte[] kind;
        final List<Declared> methods = new ArrayList<>();

        Source(String name, String text) {
            this.name = name;
            this.text = text;
            this.kind = L10nCatalogTest.classify(text);
            Matcher found = DECLARATION.matcher(text);
            while (found.find()) {
                String type = found.group(1);
                String method = found.group(2);
                if (!code(found.start()) || KEYWORDS.contains(method) || KEYWORDS.contains(type)) continue;
                int paren = found.end() - 1;
                int shut = closing(paren, '(', ')');
                if (shut < 0) continue;
                int body = skipBlank(shut + 1);
                if (text.startsWith("throws", body)) body = text.indexOf('{', body);
                if (body < 0 || text.charAt(body) != '{' || !code(body)) continue;
                List<String> names = new ArrayList<>();
                for (int[] part : parts(paren + 1, shut)) {
                    String parameter = text.substring(part[0], part[1]).replaceAll("@\\w+(\\([^)]*\\))?", "").trim();
                    if (!parameter.isEmpty()) names.add(parameter.substring(parameter.lastIndexOf(' ') + 1));
                }
                methods.add(new Declared(method, type, names, found.start(2), body, closing(body, '{', '}')));
            }
        }

        boolean code(int at) {
            return kind[at] == L10nCatalogTest.CODE;
        }

        int closing(int open, char opens, char closes) {
            int depth = 0;
            for (int at = open; at < text.length(); at++) {
                if (!code(at)) continue;
                if (text.charAt(at) == opens) depth++;
                if (text.charAt(at) == closes && --depth == 0) return at;
            }
            return -1;
        }

        int skipBlank(int at) {
            while (at < text.length() && (Character.isWhitespace(text.charAt(at)) || kind[at] == L10nCatalogTest.COMMENT)) at++;
            return at;
        }

        /** The comma-separated parts between [start] and [end], brackets and generics kept whole. */
        List<int[]> parts(int start, int end) {
            List<int[]> parts = new ArrayList<>();
            int depth = 0;
            int from = start;
            for (int at = start; at < end; at++) {
                if (!code(at)) continue;
                char c = text.charAt(at);
                if ("([{<".indexOf(c) >= 0) depth++;
                else if (")]}>".indexOf(c) >= 0) depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(new int[]{from, at});
                    from = at + 1;
                }
            }
            parts.add(new int[]{from, end});
            return parts;
        }

        /** Where the statement or argument starting at [from] ends: its semicolon, or a bracket it doesn't open. */
        int expressionEnd(int from, int limit) {
            int depth = 0;
            for (int at = from; at < limit; at++) {
                if (!code(at)) continue;
                char c = text.charAt(at);
                if ("([{".indexOf(c) >= 0) depth++;
                else if (")]}".indexOf(c) >= 0 && --depth < 0) return at;
                else if (c == ';' && depth == 0) return at;
            }
            return limit;
        }

        /** The innermost method whose body holds [at]. */
        Declared enclosing(int at) {
            Declared best = null;
            for (Declared method : methods) {
                if (method.open < at && at < method.close && (best == null || method.open > best.open)) best = method;
            }
            return best;
        }
    }

    /** Every English text that reaches a toast in [sources], with the toast call it was found from. */
    private static final class Scan {
        private final Map<String, Source> byClass = new LinkedHashMap<>();
        private final Map<String, String> found = new TreeMap<>();

        Scan(Map<String, String> sources) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                String name = source.getKey();
                byClass.put(name.substring(0, name.length() - ".java".length()), new Source(name, source.getValue()));
            }
        }

        Map<String, String> toasts() {
            for (Source source : byClass.values()) {
                Matcher call = TOAST.matcher(source.text);
                while (call.find()) {
                    if (!source.code(call.start())) continue;
                    Declared in = source.enclosing(call.start());
                    // Utils' own showToastShort and showToastLong hand on what their callers gave them.
                    if (in != null && in.name.startsWith("showToast")) continue;
                    int open = call.end() - 1;
                    String where = source.name + ":" + (lineOf(source.text, call.start()));
                    follow(source, open + 1, source.closing(open, '(', ')'), DEPTH, new HashSet<>(), where);
                }
            }
            return found;
        }

        private void follow(Source source, int start, int end, int depth, Set<String> seen, String where) {
            String text = source.text;
            Set<Integer> inL10n = new HashSet<>();
            Matcher l10n = L10N_CALL.matcher(text).region(start, end);
            while (l10n.find()) {
                if (!source.code(l10n.start())) continue;
                int close = source.closing(l10n.end() - 1, '(', ')');
                for (String key : L10nCatalogTest.literalsIn(text, source.kind, l10n.end(), close, null)) found.putIfAbsent(key, where);
                for (int at = l10n.start(); at <= close; at++) inL10n.add(at);
            }
            if (depth == 0) return;
            Declared in = source.enclosing(start);
            Matcher name = NAME.matcher(text.substring(start, end));
            while (name.find()) {
                int at = start + name.start(2);
                String qualifier = name.group(1);
                String called = name.group(2);
                boolean call = name.group(3) != null;
                if (!source.code(at) || inL10n.contains(at) || KEYWORDS.contains(called) || PASSED_THROUGH.contains(called)) continue;
                if (!seen.add(source.name + "#" + called + (call ? "()" : "@" + at))) continue;
                if (call) {
                    followReturns(qualifier, called, source, depth, seen, where);
                } else if (qualifier == null && in != null) {
                    followLocal(source, in, called, depth, seen, where);
                }
            }
        }

        /** The returns of each String method [called] could be: in [source], the named class, or any class for obj.method(). */
        private void followReturns(String qualifier, String called, Source source, int depth, Set<String> seen, String where) {
            List<Source> owners = new ArrayList<>();
            if (qualifier == null || qualifier.equals("this")) owners.add(source);
            else if (byClass.containsKey(qualifier)) owners.add(byClass.get(qualifier));
            else owners.addAll(byClass.values());
            for (Source owner : owners) {
                for (Declared method : owner.methods) {
                    if (!method.name.equals(called) || !(method.returns.equals("String") || method.returns.equals("CharSequence"))) continue;
                    Matcher returned = RETURN.matcher(owner.text).region(method.open, method.close);
                    while (returned.find()) {
                        if (!owner.code(returned.start())) continue;
                        follow(owner, returned.end(), owner.expressionEnd(returned.end(), method.close), depth - 1, seen,
                                where + " > " + called + "()");
                    }
                }
            }
        }

        /** What a local is set to in [in], or, for a parameter, what each caller passes in its place. */
        private void followLocal(Source source, Declared in, String local, int depth, Set<String> seen, String where) {
            Matcher set = Pattern.compile("\\b" + Pattern.quote(local) + "\\s*=(?!=)").matcher(source.text).region(in.open, in.close);
            boolean assigned = false;
            while (set.find()) {
                if (!source.code(set.start())) continue;
                assigned = true;
                follow(source, set.end(), source.expressionEnd(set.end(), in.close), depth - 1, seen, where + " > " + local);
            }
            int index = in.parameters.indexOf(local);
            if (assigned || index < 0) return;
            Pattern calls = Pattern.compile("(?<!\\w)" + Pattern.quote(in.name) + "\\s*\\(");
            for (Source caller : byClass.values()) {
                Matcher call = calls.matcher(caller.text);
                while (call.find()) {
                    if (!caller.code(call.start()) || isDeclaration(caller, call.start())) continue;
                    int open = call.end() - 1;
                    List<int[]> arguments = caller.parts(open + 1, caller.closing(open, '(', ')'));
                    if (index >= arguments.size()) continue;
                    int[] argument = arguments.get(index);
                    follow(caller, argument[0], argument[1], depth - 1, seen, where + " > " + in.name + "(" + local + ")");
                }
            }
        }

        private static boolean isDeclaration(Source source, int at) {
            for (Declared method : source.methods) if (method.nameAt == at) return true;
            return false;
        }

        private static int lineOf(String text, int at) {
            int line = 1;
            for (int index = 0; index < at; index++) if (text.charAt(index) == '\n') line++;
            return line;
        }
    }
}
