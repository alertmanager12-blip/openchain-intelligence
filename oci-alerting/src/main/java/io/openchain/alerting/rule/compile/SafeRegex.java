package io.openchain.alerting.rule.compile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ReDoS-hardened regular expression for the MATCHES operator. Three independent defences:
 * <ol>
 *   <li>static rejection of catastrophic shapes at validation: backreferences, nested unbounded quantifiers
 *       ({@code (a+)+}, {@code ((ab)*c)*}) and ambiguous alternations under an unbounded quantifier ({@code (a|ab)*});</li>
 *   <li>a cap on pattern length and on input length (longer inputs never match);</li>
 *   <li>a step budget: matching runs over a {@link CharSequence} that aborts after N character reads, which bounds
 *       even the polynomial cases the heuristic lets through ({@code .*.*.*x}).</li>
 * </ol>
 * The pattern is pre-compiled once. MATCHES semantics: the whole value must match.
 */
public final class SafeRegex {

    /** Raised when a single evaluation exceeds its step budget. */
    public static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() { super("regex step budget exceeded", null, false, false); }
    }

    private final Pattern pattern;
    private final int maxInputLength;
    private final long maxSteps;

    private SafeRegex(Pattern pattern, int maxInputLength, long maxSteps) {
        this.pattern = pattern;
        this.maxInputLength = maxInputLength;
        this.maxSteps = maxSteps;
    }

    /** @throws IllegalArgumentException with a human readable reason if the pattern is invalid or unsafe */
    public static SafeRegex compile(String regex, int maxPatternLength, int maxInputLength, long maxSteps) {
        if (regex == null || regex.isEmpty()) throw new IllegalArgumentException("empty pattern");
        if (regex.length() > maxPatternLength)
            throw new IllegalArgumentException("pattern longer than " + maxPatternLength + " characters");
        String unsafe = unsafeReason(regex);
        if (unsafe != null) throw new UnsafePatternException(unsafe);
        try {
            return new SafeRegex(Pattern.compile(regex), maxInputLength, maxSteps);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid pattern: " + e.getDescription());
        }
    }

    /** Distinguishes "dangerous" from "syntactically wrong" for error reporting. */
    public static final class UnsafePatternException extends IllegalArgumentException {
        UnsafePatternException(String m) { super(m); }
    }

    /** @throws BudgetExceeded when the evaluation needs more than the step budget */
    public boolean matches(String input) {
        if (input == null || input.length() > maxInputLength) return false;
        Matcher m = pattern.matcher(new BudgetedSequence(input, 0, input.length(), new long[]{maxSteps}));
        return m.matches();
    }

    public String pattern() { return pattern.pattern(); }

    // ---------------------------------------------------------------- static analysis

    private static final class Frame {
        boolean unbounded;           // contains an unbounded quantifier (directly or nested)
        boolean alternation;
        final StringBuilder branchStarts = new StringBuilder();
        boolean ambiguousBranches;   // a branch starts with a non-literal or two branches share a first char
        boolean expectBranchStart = true;
    }

    /** @return null if no catastrophic construct is detected, otherwise the reason */
    static String unsafeReason(String re) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame());
        int n = re.length();
        int i = 0;
        while (i < n) {
            char c = re.charAt(i);
            Frame top = stack.peek();
            if (c == '\\') {
                if (i + 1 < n) {
                    char d = re.charAt(i + 1);
                    if ((d >= '1' && d <= '9') || d == 'k') return "backreferences are not allowed";
                    if (d == 'Q') return "\\Q..\\E quoting is not allowed (use explicit escapes)";
                    noteBranchStart(top, Character.isLetterOrDigit(d) ? '\0' : d);
                }
                i += 2;
                continue;
            }
            if (c == '[') {
                noteBranchStart(top, '\0');
                i = skipClass(re, i);
                if (i < 0) return null; // let Pattern.compile report the syntax error
                continue;
            }
            if (c == '(') {
                Frame f = new Frame();
                stack.push(f);
                i++;
                if (i < n && re.charAt(i) == '?') i = skipGroupPrefix(re, i + 1, stack);
                continue;
            }
            if (c == ')') {
                if (stack.size() == 1) return null; // unbalanced, Pattern.compile reports it
                Frame closed = stack.pop();
                i++;
                int q = quantifierAt(re, i);
                boolean outerUnbounded = q == 2;
                boolean repeated = q >= 1;
                if (repeated && closed.unbounded) return "nested quantifiers (e.g. (a+)+) are not allowed";
                if (outerUnbounded && closed.alternation && closed.ambiguousBranches)
                    return "ambiguous alternation under a repeating quantifier (e.g. (a|ab)*) is not allowed";
                Frame parent = stack.peek();
                parent.unbounded |= closed.unbounded || outerUnbounded;
                noteBranchStart(parent, '\0');
                continue;
            }
            if (c == '|') {
                top.alternation = true;
                top.expectBranchStart = true;
                i++;
                continue;
            }
            int q = quantifierAt(re, i);
            if (q != 0) {
                if (q == 2) top.unbounded = true;
                i = skipQuantifier(re, i);
                continue;
            }
            noteBranchStart(top, c == '.' ? '\0' : c);
            i++;
        }
        return null;
    }

    /** Skips the construct after "(?" : ":", "=", "!", ">", "<=", "<!", "<name>", or inline flags "i-s:" / "i)". */
    private static int skipGroupPrefix(String re, int i, Deque<Frame> stack) {
        int n = re.length();
        if (i >= n) return n;
        char c = re.charAt(i);
        if (c == ':' || c == '=' || c == '!' || c == '>') return i + 1;
        if (c == '<') {
            if (i + 1 < n && (re.charAt(i + 1) == '=' || re.charAt(i + 1) == '!')) return i + 2;
            int close = re.indexOf('>', i);
            return close < 0 ? n : close + 1;
        }
        int j = i;
        while (j < n && (Character.isLetter(re.charAt(j)) || re.charAt(j) == '-')) j++;
        if (j < n && re.charAt(j) == ')') { stack.pop(); return j + 1; } // inline flags, not a group
        return Math.min(j + 1, n);
    }

    private static void noteBranchStart(Frame f, char first) {
        if (!f.expectBranchStart) return;
        f.expectBranchStart = false;
        if (first == '\0' || f.branchStarts.indexOf(String.valueOf(first)) >= 0) f.ambiguousBranches = true;
        else f.branchStarts.append(first);
    }

    /** 0 = no quantifier, -1 = optional (?), 1 = bounded repeat ({n,m}), 2 = unbounded (* + {n,} or large {n,m}) */
    private static int quantifierAt(String re, int i) {
        if (i >= re.length()) return 0;
        char c = re.charAt(i);
        if (c == '*' || c == '+') return 2;
        if (c == '?') return -1; // optional: never multiplies backtracking paths
        if (c == '{') {
            int close = re.indexOf('}', i);
            if (close < 0) return 0;
            String body = re.substring(i + 1, close);
            if (body.matches("\\d+,")) return 2;
            if (body.matches("\\d+(,\\d+)?")) {
                String[] p = body.split(",");
                int hi = Integer.parseInt(p[p.length - 1].length() > 6 ? "999999" : p[p.length - 1]);
                return hi > 1 ? (hi > 64 ? 2 : 1) : 0;
            }
        }
        return 0;
    }

    private static int skipQuantifier(String re, int i) {
        char c = re.charAt(i);
        int next = c == '{' ? re.indexOf('}', i) + 1 : i + 1;
        if (next < re.length() && (re.charAt(next) == '?' || re.charAt(next) == '+')) next++; // lazy / possessive
        return next;
    }

    private static int skipClass(String re, int i) {
        int depth = 0;
        int n = re.length();
        int j = i;
        while (j < n) {
            char c = re.charAt(j);
            if (c == '\\') { j += 2; continue; }
            if (c == '[') depth++;
            else if (c == ']' && j > i + 1 && --depth == 0) return j + 1;
            j++;
        }
        return -1;
    }

    // ---------------------------------------------------------------- runtime budget

    private record BudgetedSequence(String s, int start, int end, long[] budget) implements CharSequence {
        @Override public int length() { return end - start; }
        @Override public char charAt(int index) {
            if (--budget[0] < 0) throw new BudgetExceeded();
            return s.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            return new BudgetedSequence(s, start + from, start + to, budget);
        }
        @Override public String toString() { return s.substring(start, end); }
    }
}
