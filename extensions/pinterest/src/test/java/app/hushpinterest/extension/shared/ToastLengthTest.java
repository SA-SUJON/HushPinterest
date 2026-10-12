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
    // A chain that runs out of steps fails theScanFollowsTextsToTheirToasts rather than passing unread.
    private static final int DEPTH = 6;

    private static final Pattern TOAST = Pattern.compile("\\bshowToast(?:Short|Long)?\\s*\\(");
    private static final Pattern L10N_CALL = Pattern.compile("\\bL10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");
    private static final Pattern DECLARATION = Pattern.compile("([\\w<>\\[\\].?]+)\\s+(\\w+)\\s*\\(");
    // A name with the dotted chain in front of it, so a.b.method() reads as b's method.
    private static final Pattern NAME = Pattern.compile("(?<!\\w)((?:\\w+\\s*\\.\\s*)*)([A-Za-z_]\\w*)\\b(\\s*\\()?");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    private static final Pattern NUMBER = Pattern.compile("%(?:\\d+\\$)?d");
    private static final Pattern WORDS = Pattern.compile("%(?:\\d+\\$)?s");
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList("if", "for", "while", "switch", "catch",
            "synchronized", "return", "new", "throw", "case", "else", "try", "do", "super", "this", "null", "true", "false"));
    // L10n's and String's own methods, which hand a text through rather than make one.
    private static final Set<String> PASSED_THROUGH = new HashSet<>(Arrays.asList("L10n", "t", "f", "quantity",
            "isolate", "format", "valueOf", "toString", "equals", "length", "isEmpty", "trim"));
    // Logger's methods whose lines go to the log only. Its toast shows errors.
    private static final Set<String> LOGGED_ONLY = new HashSet<>(Arrays.asList("printDebug", "printInfo",
            "diagnosticDebug", "diagnosticInfo"));

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
        Scan scan = new Scan(realSources());
        Map<String, String> toasts = scan.toasts();
        // A scan that quietly found nothing would pass every other test here.
        assertTrue("found " + toasts.size(), toasts.size() > 100);
        assertEquals("ran out of steps before reaching a text:\n" + String.join("\n", scan.cutShort), 0, scan.cutShort.size());
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
                + "  void chained() { Utils.showToastLong(app.B.chained()); }\n"
                + "  void added(int n) { String said = L10n.t(\"Added\"); if (n > 1) said += L10n.t(\"Plus\"); Utils.showToastShort(said); }\n"
                + "  void built() { StringBuilder said = new StringBuilder(L10n.t(\"Built\")); said.append(L10n.t(\"Appended\"));"
                + " Utils.showToastShort(said.toString()); }\n"
                + "  void teller() { tell(L10n.t(\"Told\")); }\n"
                + "  private void tell(String text) { if (text == null) text = L10n.t(\"Fallback\"); Utils.showToastLong(text); }\n"
                + "  void made() { Utils.showToastLong(B.make().label()); }\n"
                + "  void handed() { hand(x -> x, L10n.t(\"After a lambda\")); }\n"
                + "  private void hand(Function<String, String> f, String error) { Utils.showToastLong(error); }\n"
                + "  // Utils.showToastLong(L10n.t(\"Commented out\"));\n"
                + "}\n");
        sources.put("B.java", "class B {\n"
                + "  static String other() { return L10n.t(\"Other class\"); }\n"
                + "  static String chained() { return L10n.t(\"Chained\"); }\n"
                + "  static B make() { return new B(); }\n"
                + "  String label() { return L10n.t(\"Label\"); }\n"
                + "  static String fromParent() { return L10n.t(\"Inherited\"); }\n"
                + "  static String unrelated() { return L10n.t(\"Not a toast\"); }\n"
                + "}\n");
        sources.put("C.java", "class C extends B {\n"
                + "  void inherited() { Utils.showToastLong(fromParent()); }\n"
                + "}\n");
        Scan scan = new Scan(sources);
        assertEquals(new HashSet<>(Arrays.asList("Direct text", "Local", "Passed %s", "x", "Helper", "Other class", "Chained",
                "Added", "Plus", "Built", "Appended", "Told", "Fallback", "Label", "Inherited", "After a lambda")),
                scan.toasts().keySet());
        assertEquals(0, scan.cutShort.size());
    }

    @Test
    public void aChainLongerThanTheStepsIsReported() {
        StringBuilder code = new StringBuilder("class D {\n  void toast() { Utils.showToastLong(h0()); }\n");
        for (int step = 0; step < DEPTH; step++) {
            code.append("  static String h").append(step).append("() { return h").append(step + 1).append("(); }\n");
        }
        code.append("  static String h").append(DEPTH).append("() { return L10n.t(\"Deep\"); }\n}\n");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("D.java", code.toString());
        Scan scan = new Scan(sources);
        assertTrue(scan.toasts().isEmpty());
        assertEquals(1, scan.cutShort.size());
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

    /** A stretch of code a name hands on to, and how the trail to a toast shows it. */
    private static final class Step {
        final Source source;
        final int start;
        final int end;
        final String label;

        Step(Source source, int start, int end, String label) {
            this.source = source;
            this.start = start;
            this.end = end;
            this.label = label;
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

        boolean readsText(int start, int end) {
            Matcher call = L10N_CALL.matcher(text).region(start, end);
            while (call.find()) if (code(call.start())) return true;
            return false;
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

        private boolean typeStarts(int at) {
            return at < text.length() && (Character.isUpperCase(text.charAt(at)) || text.charAt(at) == '?' || text.charAt(at) == '>');
        }

        /** The comma-separated parts between [start] and [end], brackets and generics kept whole. */
        List<int[]> parts(int start, int end) {
            List<int[]> parts = new ArrayList<>();
            int depth = 0;
            int angle = 0;
            int from = start;
            for (int at = start; at < end; at++) {
                if (!code(at)) continue;
                char c = text.charAt(at);
                if ("([{".indexOf(c) >= 0) depth++;
                else if (")]}".indexOf(c) >= 0) depth--;
                // A type's <...>, where a lambda's -> or a comparison is no bracket.
                else if (c == '<' && typeStarts(skipBlank(at + 1))) angle++;
                else if (c == '>' && angle > 0 && text.charAt(at - 1) != '-') angle--;
                else if (c == ',' && depth == 0 && angle == 0) {
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
        // Names still worth following when a chain ran out of steps.
        final List<String> cutShort = new ArrayList<>();

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
            Declared in = source.enclosing(start);
            Matcher name = NAME.matcher(text.substring(start, end));
            while (name.find()) {
                int at = start + name.start(2);
                String chain = name.group(1).replaceAll("\\s", "");
                String called = name.group(2);
                boolean call = name.group(3) != null;
                if (!source.code(at) || inL10n.contains(at) || KEYWORDS.contains(called)) continue;
                String qualifier;
                if (PASSED_THROUGH.contains(called)) {
                    // said.toString() and text.trim() hand on the local in front of them.
                    if (!call || !chain.matches("\\w+\\.")) continue;
                    called = chain.substring(0, chain.length() - 1);
                    call = false;
                    qualifier = null;
                } else if (chain.isEmpty()) {
                    // b().method() could be any class's method.
                    qualifier = dotBefore(text, start + name.start()) ? "" : null;
                } else {
                    String[] links = chain.split("\\.");
                    qualifier = links[links.length - 1];
                }
                String key = source.name + "#" + (qualifier == null ? "" : qualifier + ".") + called + (call ? "()" : "@" + at);
                if (depth == 0) {
                    // Out of steps: say so when the very next one would have read a text.
                    if (seen.contains(key)) continue;
                    for (Step step : steps(source, in, qualifier, called, call)) {
                        if (step.source.readsText(step.start, step.end)) {
                            cutShort.add(where + " > " + step.label);
                            break;
                        }
                    }
                    continue;
                }
                if (!seen.add(key)) continue;
                for (Step step : steps(source, in, qualifier, called, call)) {
                    follow(step.source, step.start, step.end, depth - 1, seen, where + " > " + step.label);
                }
            }
        }

        /** Where [called] hands on from: a String method's returns, what a local is set to, or what callers pass a parameter. */
        private List<Step> steps(Source source, Declared in, String qualifier, String called, boolean call) {
            List<Step> steps = new ArrayList<>();
            if (call) {
                for (Source owner : owners(qualifier, called, source)) {
                    for (Declared method : owner.methods) {
                        if (!method.name.equals(called) || !textual(method)) continue;
                        Matcher returned = RETURN.matcher(owner.text).region(method.open, method.close);
                        while (returned.find()) {
                            if (!owner.code(returned.start())) continue;
                            steps.add(new Step(owner, returned.end(), owner.expressionEnd(returned.end(), method.close), called + "()"));
                        }
                    }
                }
                return steps;
            }
            if (qualifier != null || in == null) return steps;
            Matcher set = setting(called).matcher(source.text).region(in.open, in.close);
            while (set.find()) {
                if (source.code(set.start())) steps.add(new Step(source, set.end(), source.expressionEnd(set.end(), in.close), called));
            }
            int index = in.parameters.indexOf(called);
            // Each call of Utils' showToast methods is a toast of its own, read from there.
            if (index < 0 || in.name.startsWith("showToast") || LOGGED_ONLY.contains(in.name)) return steps;
            Pattern calls = Pattern.compile("(?<!\\w)" + Pattern.quote(in.name) + "\\s*\\(");
            for (Source caller : byClass.values()) {
                Matcher found = calls.matcher(caller.text);
                while (found.find()) {
                    if (!caller.code(found.start()) || isDeclaration(caller, found.start())) continue;
                    int open = found.end() - 1;
                    List<int[]> arguments = caller.parts(open + 1, caller.closing(open, '(', ')'));
                    if (index >= arguments.size()) continue;
                    int[] argument = arguments.get(index);
                    steps.add(new Step(caller, argument[0], argument[1], in.name + "(" + called + ")"));
                }
            }
            return steps;
        }

        /** Where a method [called] with [qualifier] in [source] could be declared. */
        private List<Source> owners(String qualifier, String called, Source source) {
            List<Source> owners = new ArrayList<>();
            if (qualifier == null || qualifier.equals("this")) {
                for (Declared method : source.methods) {
                    if (method.name.equals(called)) {
                        owners.add(source);
                        return owners;
                    }
                }
                // One the class doesn't declare comes from its parent or a static import.
                Matcher from = Pattern.compile("\\bextends\\s+(\\w+)|\\bimport\\s+static\\s+[\\w.]*?(\\w+)\\s*\\.\\s*(?:"
                        + Pattern.quote(called) + "|\\*)\\s*;").matcher(source.text);
                while (from.find()) {
                    Source owner = byClass.get(from.group(1) != null ? from.group(1) : from.group(2));
                    if (owner != null && owner != source) owners.add(owner);
                }
            } else if (byClass.containsKey(qualifier)) {
                owners.add(byClass.get(qualifier));
            } else {
                owners.addAll(byClass.values());
            }
            return owners;
        }

        private static Pattern setting(String local) {
            return Pattern.compile("\\b" + Pattern.quote(local) + "\\s*(?:\\+?=(?!=)|\\.\\s*append\\s*\\()");
        }

        private static boolean textual(Declared method) {
            return method.returns.equals("String") || method.returns.equals("CharSequence");
        }

        private static boolean dotBefore(String text, int at) {
            int index = at - 1;
            while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
            return index >= 0 && text.charAt(index) == '.';
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
