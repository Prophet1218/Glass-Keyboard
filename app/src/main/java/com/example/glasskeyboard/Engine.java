package com.example.glasskeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Spelling, autocorrect and suggestion engine for one language.
 *
 * Words are looked up in a frequency trie with a keyboard-aware, weighted edit distance:
 * a missing accent (a/á, o/ö/ő, u/ü/ű…) is almost free, a neighbouring key is cheap,
 * a doubled or missing double letter is cheap, swapped letters are cheap, anything else costs
 * a full edit. Hungarian inflected and compound words that are not in the word list are
 * recognised by a small suffix and compound check, so they are not "corrected" away.
 */
public final class Engine {

    public static final class Cand {
        public final String word;
        public final double cost;
        public final int freq;
        public final boolean completion;
        public double score;

        Cand(String word, double cost, int freq, boolean completion) {
            this.word = word;
            this.cost = cost;
            this.freq = freq;
            this.completion = completion;
        }

        @Override
        public String toString() {
            return word + String.format(Locale.ROOT, "(c=%.2f f=%d s=%.2f%s)", cost, freq, score, completion ? " +" : "");
        }
    }

    public static final class Result {
        /** Ranked suggestions, already in the right letter case. */
        public final List<String> suggestions = new ArrayList<>();
        /** What the word will be replaced with on space/punctuation, or null. */
        public String correction;
        /** The typed word is a known/acceptable word. */
        public boolean typedValid;
    }

    public final boolean hungarian;
    public final Locale locale;
    private final Dict dict;
    private final UserDict user;
    private final HashMap<String, String> proper = new HashMap<>();
    private final float[] keyX = new float[128];
    private final float[] keyY = new float[128];

    public Engine(boolean hungarian, Dict dict, UserDict user, List<String> properNouns) {
        this.hungarian = hungarian;
        this.locale = hungarian ? new Locale("hu", "HU") : Locale.US;
        this.dict = dict;
        this.user = user;
        if (properNouns != null) {
            for (String p : properNouns) {
                String s = p.trim();
                if (!s.isEmpty()) proper.put(s.toLowerCase(locale), s);
            }
        }
        String[] rows = hungarian
                ? new String[]{"qwertzuiop", "asdfghjkl", "yxcvbnm"}
                : new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] off = {0f, 0.5f, 1.5f};
        for (int i = 0; i < 128; i++) keyX[i] = -100f;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                keyX[ch] = off[r] + c;
                keyY[ch] = r;
            }
        }
    }

    // ---------------------------------------------------------------- letters

    public static char base(char c) {
        switch (c) {
            case 'á': case 'ä': case 'à': case 'â': return 'a';
            case 'é': case 'è': case 'ê': case 'ë': return 'e';
            case 'í': case 'ì': case 'î': case 'ï': return 'i';
            case 'ó': case 'ö': case 'ő': case 'ò': case 'ô': return 'o';
            case 'ú': case 'ü': case 'ű': case 'ù': case 'û': return 'u';
            case '’': return '\'';
            default: return c;
        }
    }

    private static boolean accented(char c) {
        return base(c) != c && c != '’';
    }

    /** The word without accents and apostrophes. */
    public static String looseKey(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = base(s.charAt(i));
            if (c != '\'') sb.append(c);
        }
        return sb.toString();
    }

    private boolean near(char a, char b) {
        if (a >= 128 || b >= 128) return false;
        float ax = keyX[a], bx = keyX[b];
        if (ax < -50 || bx < -50) return false;
        float dx = ax - bx, dy = keyY[a] - keyY[b];
        return dx * dx + dy * dy <= 1.6f;
    }

    // ---------------------------------------------------------------- edit costs

    private double subCost(char t, char d, int i) {
        if (t == d) return 0;
        char bt = base(t), bd = base(d);
        double c;
        if (bt == bd) {
            if (t == bt) c = 0.12;          // typed without accent, word has one
            else if (d == bd) c = 0.45;     // typed an accent the word does not have
            else c = 0.22;                  // wrong accent (ö instead of ő…)
            return c;
        }
        if (near(bt, bd)) c = 0.6;
        else if (hungarian && ((bt == 'y' && bd == 'z') || (bt == 'z' && bd == 'y'))) c = 0.6;
        else c = 1.0;
        if (i == 0) c += 0.35;
        return c;
    }

    private double delCost(char[] t, int i) {
        char c = t[i];
        double v;
        if (c == '\'' || c == '-') v = 0.3;
        else if (i > 0 && t[i - 1] == c) v = 0.35;
        else if ((i > 0 && near(base(c), base(t[i - 1]))) || (i + 1 < t.length && near(base(c), base(t[i + 1])))) v = 0.55;
        else v = 0.85;
        if (i == 0) v += 0.35;
        return v;
    }

    private static double insCost(char d, char prevD, int i) {
        if (d == '\'') return 0.1;
        if (d == '-') return 0.3;
        double v = d == prevD ? 0.35 : 0.66;
        if (i == 0) v += 0.35;
        return v;
    }

    private static double maxCost(int n) {
        if (n <= 1) return 0.15;
        if (n == 2) return 0.45;
        if (n == 3) return 0.9;
        if (n == 4) return 1.15;
        if (n <= 6) return 1.45;
        if (n <= 9) return 1.85;
        return 2.2;
    }

    // ---------------------------------------------------------------- trie search

    private char[] t;
    private int n;
    private double[][] rows;
    private double limit;
    private double completionLimit;
    private final StringBuilder path = new StringBuilder();
    private HashMap<String, Cand> found;
    private ArrayList<Object[]> prefixNodes;

    private void dfs(int node, int depth, char pd) {
        int f = dict.first(node), e = f + dict.count(node);
        double[] prev = rows[depth];
        double[] cur = rows[depth + 1];
        double[] pp = depth > 0 ? rows[depth - 1] : null;
        for (int c = f; c < e; c++) {
            char d = dict.ch(c);
            cur[0] = prev[0] + insCost(d, pd, 0);
            double min = cur[0];
            for (int i = 1; i <= n; i++) {
                double v = prev[i - 1] + subCost(t[i - 1], d, i - 1);
                double w = prev[i] + insCost(d, pd, i);
                if (w < v) v = w;
                w = cur[i - 1] + delCost(t, i - 1);
                if (w < v) v = w;
                if (pp != null && i > 1 && t[i - 2] == d && t[i - 1] == pd && d != pd) {
                    w = pp[i - 2] + 0.55;
                    if (w < v) v = w;
                }
                cur[i] = v;
                if (v < min) min = v;
            }
            if (min > limit + 0.45) continue; // slack: a swap of two letters only pays off one step later
            path.append(d);
            double endCost = cur[n];
            if (endCost <= limit) {
                int fr = dict.freq(c);
                if (fr > 0) {
                    String w = path.toString();
                    Cand old = found.get(w);
                    if (old == null || old.cost > endCost) found.put(w, new Cand(w, endCost, fr, false));
                }
            }
            if (endCost <= completionLimit && prefixNodes != null && depth + 1 >= Math.min(n, 2)) {
                prefixNodes.add(new Object[]{c, endCost, path.toString()});
            }
            if (depth + 2 < rows.length) dfs(c, depth + 1, d);
            path.setLength(path.length() - 1);
        }
    }

    private HashMap<String, Cand> search(String w, double maxCost, boolean completions) {
        t = w.toCharArray();
        n = t.length;
        limit = maxCost;
        completionLimit = Math.min(0.5, maxCost);
        int depth = n + 4;
        rows = new double[depth + 1][n + 1];
        rows[0][0] = 0;
        for (int i = 1; i <= n; i++) rows[0][i] = rows[0][i - 1] + delCost(t, i - 1);
        found = new HashMap<>();
        prefixNodes = completions ? new ArrayList<>() : null;
        path.setLength(0);
        dfs(0, 0, '\0');
        if (completions) addCompletions();
        HashMap<String, Cand> out = found;
        found = null;
        return out;
    }

    private void addCompletions() {
        prefixNodes.sort((a, b) -> {
            int c = Double.compare((Double) a[1], (Double) b[1]);
            if (c != 0) return c;
            return dict.maxSub((Integer) b[0]) - dict.maxSub((Integer) a[0]);
        });
        int used = 0;
        HashSet<Integer> seen = new HashSet<>();
        for (Object[] p : prefixNodes) {
            if (used >= 8) break;
            int node = (Integer) p[0];
            if (!seen.add(node)) continue;
            used++;
            double cost = (Double) p[1];
            String prefix = (String) p[2];
            // Best-first walk: subtree entries are keyed by their best frequency, word entries by
            // their own frequency, so words come out in descending frequency order.
            PriorityQueue<Object[]> q = new PriorityQueue<>((a, b) -> (Integer) b[1] - (Integer) a[1]);
            int f0 = dict.first(node), e0 = f0 + dict.count(node);
            for (int c = f0; c < e0; c++) q.add(new Object[]{c, dict.maxSub(c), prefix + dict.ch(c), Boolean.FALSE});
            int got = 0, steps = 0;
            while (!q.isEmpty() && got < 4 && steps < 600) {
                steps++;
                Object[] it = q.poll();
                int c = (Integer) it[0];
                String s = (String) it[2];
                if ((Boolean) it[3]) {
                    got++;
                    if (!found.containsKey(s)) found.put(s, new Cand(s, cost, (Integer) it[1], true));
                    continue;
                }
                int fr = dict.freq(c);
                if (fr > 0) q.add(new Object[]{c, fr, s, Boolean.TRUE});
                int f = dict.first(c), e = f + dict.count(c);
                for (int k = f; k < e; k++) q.add(new Object[]{k, dict.maxSub(k), s + dict.ch(k), Boolean.FALSE});
            }
        }
    }

    // ---------------------------------------------------------------- user words

    private void addUserCandidates(String w, double maxCost, boolean completions, HashMap<String, Cand> out) {
        for (String u : user.knownWords()) {
            if (Math.abs(u.length() - w.length()) > 3 && !(completions && u.startsWith(w))) continue;
            double c = distance(w, u);
            int fr = userFreq(u);
            if (c <= maxCost) {
                Cand old = out.get(u);
                if (old == null || old.cost > c) out.put(u, new Cand(u, c, Math.max(fr, old == null ? 0 : old.freq), false));
            }
        }
        if (completions && w.length() >= 1) {
            for (String u : user.withPrefix(w, 1)) {
                if (u.length() > w.length() && !out.containsKey(u)) out.put(u, new Cand(u, 0, userFreq(u), true));
            }
        }
    }

    private int userFreq(String u) {
        int c = user.count(u);
        int d = dict.freq(u);
        return Math.max(d, Math.min(200, 110 + 15 * Math.min(c, 6)));
    }

    /** Weighted edit distance between typed word and a candidate (same costs as the trie search). */
    public double distance(String typed, String word) {
        char[] tt = typed.toCharArray();
        int nn = tt.length, m = word.length();
        double[][] dp = new double[m + 1][nn + 1];
        for (int i = 1; i <= nn; i++) dp[0][i] = dp[0][i - 1] + delCost(tt, i - 1);
        for (int j = 1; j <= m; j++) {
            char d = word.charAt(j - 1);
            char pd = j > 1 ? word.charAt(j - 2) : '\0';
            dp[j][0] = dp[j - 1][0] + insCost(d, pd, 0);
            for (int i = 1; i <= nn; i++) {
                double v = dp[j - 1][i - 1] + subCost(tt[i - 1], d, i - 1);
                v = Math.min(v, dp[j - 1][i] + insCost(d, pd, i));
                v = Math.min(v, dp[j][i - 1] + delCost(tt, i - 1));
                if (j > 1 && i > 1 && tt[i - 2] == d && tt[i - 1] == pd && d != pd) v = Math.min(v, dp[j - 2][i - 2] + 0.55);
                dp[j][i] = v;
            }
        }
        return dp[m][nn];
    }

    // ---------------------------------------------------------------- validity

    public int freq(String lower) {
        return dict.freq(lower);
    }

    /** True when the word is acceptable as typed (dictionary, learned, or a valid inflection/compound). */
    public boolean isValid(String lower) {
        if (lower.isEmpty()) return true;
        if (dict.freq(lower) > 0 || user.isKnown(lower)) return true;
        int dash = lower.indexOf('-');
        if (dash > 0 && dash < lower.length() - 1) {
            for (String part : lower.split("-")) {
                if (!part.isEmpty() && !isValid(part)) return false;
            }
            return true;
        }
        if (!hungarian) {
            if (lower.endsWith("'s") && lower.length() > 3) return isValid(lower.substring(0, lower.length() - 2));
            if (lower.endsWith("s'") && lower.length() > 3) return isValid(lower.substring(0, lower.length() - 1));
            return false;
        }
        return huInflected(lower, 70) || huCompound(lower);
    }

    private static final String[] HU_SUFFIXES = {
            // cases
            "ban", "ben", "ba", "be", "ból", "ből", "ra", "re", "ról", "ről", "on", "en", "ön", "an",
            "nak", "nek", "hoz", "hez", "höz", "tól", "től", "nál", "nél", "val", "vel", "al", "el",
            "ért", "ig", "ként", "kor", "vá", "vé", "ul", "ül", "ot", "at", "et", "öt", "t", "n",
            "stul", "stül", "nként",
            // plural, possessive
            "k", "ok", "ak", "ek", "ök", "i", "ai", "ei", "jai", "jei", "im", "id", "ink", "itok", "itek", "ik",
            "m", "om", "am", "em", "öm", "d", "od", "ad", "ed", "öd", "ja", "je", "a", "e",
            "nk", "unk", "ünk", "tok", "tek", "tök", "otok", "atok", "etek", "ötök",
            "juk", "jük", "uk", "ük", "é", "éi", "éé", "ék",
            // verb forms
            "sz", "asz", "esz", "ol", "öl", "ni", "ani", "eni", "tam", "tem", "tál", "tél", "ttam", "ttem",
            "ott", "ett", "ött", "tt", "tunk", "tünk", "tatok", "tetek", "tak", "ték", "ták", "tuk", "tük",
            "ná", "né", "na", "ne", "nék", "nánk", "nénk", "nátok", "nétek", "nának", "nének",
            "hat", "het", "hatok", "hetek", "hatsz", "hetsz", "hatunk", "hetünk", "hatnak", "hetnek",
            "jak", "jek", "jon", "jen", "jön", "junk", "jünk", "jatok", "jetek", "janak", "jenek",
            "ják", "jék", "ván", "vén", "va", "ve", "ó", "ő", "ás", "és", "ság", "ség", "talan", "telen",
            "s", "os", "as", "es", "ös", "beli", "nyi", "ka", "ke", "cska", "cske", "bb", "abb", "ebb", "obb",
            "ul", "ül", "lag", "leg", "an", "en", "ul", "kodik", "kedik", "ozik", "ezik", "ödik", "zik",
    };
    private static final HashSet<String> HU_SUFFIX_SET = new HashSet<>();

    static {
        Collections.addAll(HU_SUFFIX_SET, HU_SUFFIXES);
    }

    private static boolean suffixChain(String s, int depth) {
        if (s.isEmpty()) return true;
        if (depth == 0) return false;
        for (int k = Math.min(6, s.length()); k >= 1; k--) {
            if (HU_SUFFIX_SET.contains(s.substring(0, k)) && suffixChain(s.substring(k), depth - 1)) return true;
        }
        return false;
    }

    /** stem (a common dictionary word) + 1–3 known suffixes, at least 2 letters of suffix. */
    private boolean huInflected(String w, int minStemFreq) {
        int len = w.length();
        if (len < 5) return false;
        for (int split = len - 2; split >= 3; split--) {
            String stem = w.substring(0, split);
            int f = dict.freq(stem);
            if ((f >= minStemFreq || user.isKnown(stem)) && suffixChain(w.substring(split), 3)) return true;
        }
        return false;
    }

    /** word1 + word2 (+ suffixes), both parts real words. */
    private boolean huCompound(String w) {
        int len = w.length();
        if (len < 6) return false;
        for (int split = 3; split <= len - 3; split++) {
            String left = w.substring(0, split);
            int fl = dict.freq(left);
            if (fl < 95 && !user.isKnown(left)) continue;
            String right = w.substring(split);
            if (dict.freq(right) >= 70 || user.isKnown(right) || huInflected(right, 85)) return true;
        }
        return false;
    }

    /** Most frequent dictionary word that differs from [w] only in accents (accents the user typed must stay). */
    private String bestVariant(String w, int minFreq) {
        bvBest = null;
        bvFreq = minFreq - 1;
        bvWord = w;
        path.setLength(0);
        bv(0, 0);
        return bvBest;
    }

    private String bvBest;
    private int bvFreq;
    private String bvWord;

    private void bv(int node, int i) {
        if (i == bvWord.length()) {
            int f = dict.freq(node);
            if (f > bvFreq) {
                bvFreq = f;
                bvBest = path.toString();
            }
            return;
        }
        char want = bvWord.charAt(i);
        int f = dict.first(node), e = f + dict.count(node);
        for (int c = f; c < e; c++) {
            if (dict.maxSub(c) <= bvFreq) continue;
            char d = dict.ch(c);
            if (d == want || (want == base(want) && base(d) == want)) {
                path.append(d);
                bv(c, i + 1);
                path.setLength(path.length() - 1);
            }
        }
    }

    /** Puts accents back into an unknown Hungarian compound / inflected word, e.g. kenyerpirito → kenyérpirító. */
    private String restoreAccents(String w) {
        int len = w.length();
        if (len < 6) return null;
        String best = null;
        int bestScore = -1;
        for (int split = 3; split <= len - 3; split++) {
            String l = bestVariant(w.substring(0, split), 95);
            if (l == null) continue;
            String rpart = w.substring(split);
            String r = bestVariant(rpart, 70);
            int score;
            if (r != null) {
                score = Math.min(dict.freq(l), dict.freq(r));
            } else {
                // right side may be inflected: variant stem + suffix
                r = null;
                score = -1;
                for (int s2 = rpart.length() - 1; s2 >= 3; s2--) {
                    String st = bestVariant(rpart.substring(0, s2), 85);
                    if (st == null) continue;
                    String suf = suffixVariant(rpart.substring(s2));
                    if (suf != null) {
                        r = st + suf;
                        score = Math.min(dict.freq(l), dict.freq(st)) - 10;
                        break;
                    }
                }
                if (r == null) continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best = l + r;
            }
        }
        // stem + suffixes (single word)
        for (int split = len - 2; split >= 3; split--) {
            String st = bestVariant(w.substring(0, split), 90);
            if (len - split < 2) continue;
            if (st == null) continue;
            String suf = suffixVariant(w.substring(split));
            if (suf != null) {
                int score = dict.freq(st) - 5;
                if (score > bestScore) {
                    bestScore = score;
                    best = st + suf;
                }
                break;
            }
        }
        if (best == null || best.equals(w)) return null;
        return best;
    }

    /** The suffix chain with accents restored (vowel harmony is taken from the suffix list itself). */
    private static String suffixVariant(String s) {
        if (s.isEmpty()) return "";
        for (int k = Math.min(6, s.length()); k >= 1; k--) {
            String head = s.substring(0, k);
            String headKey = looseKey(head);
            String match = null;
            if (HU_SUFFIX_SET.contains(head)) match = head;
            else {
                for (String suf : HU_SUFFIXES) {
                    if (suf.length() == k && looseKey(suf).equals(headKey) && accentsKept(head, suf)) {
                        match = suf;
                        break;
                    }
                }
            }
            if (match != null) {
                String rest = suffixVariant(s.substring(k));
                if (rest != null) return match + rest;
            }
        }
        return null;
    }

    private static boolean accentsKept(String typed, String word) {
        for (int i = 0; i < typed.length(); i++) {
            char a = typed.charAt(i);
            if (accented(a) && word.charAt(i) != a) return false;
        }
        return true;
    }

    /** [word] is the typed text with only accents added (and/or apostrophes added). */
    private static boolean accentOnly(String typed, String word) {
        if (!looseKey(typed).equals(looseKey(word))) return false;
        String w2 = word.replace("'", "");
        String t2 = typed.replace("'", "");
        return w2.length() == t2.length() && accentsKept(t2, w2);
    }

    /** "hogyvagy" → "hogy vagy": two very common words typed without the space. */
    private void addSplit(String w, HashMap<String, Cand> out) {
        int min = 160;
        for (int split = 1; split <= w.length() - 1; split++) {
            if (!splitPartOk(w.substring(0, split)) || !splitPartOk(w.substring(split))) continue;
            String l = bestVariant(w.substring(0, split), min);
            if (l == null) continue;
            String r = bestVariant(w.substring(split), min);
            if (r == null) continue;
            String joined = l + " " + r;
            double cost = 0.5 + 0.12 * (countDiff(w.substring(0, split), l) + countDiff(w.substring(split), r));
            int f = Math.min(dict.freq(l), dict.freq(r));
            Cand old = out.get(joined);
            if (old == null || old.cost > cost) out.put(joined, new Cand(joined, cost, f, false));
        }
    }

    private boolean splitPartOk(String part) {
        if (part.length() >= 2) return true;
        return hungarian ? part.equals("a") : part.equals("a") || part.equals("i");
    }

    private static int countDiff(String a, String b) {
        int c = 0;
        for (int i = 0; i < a.length() && i < b.length(); i++) if (a.charAt(i) != b.charAt(i)) c++;
        return c;
    }

    // ---------------------------------------------------------------- letter case

    public String applyCase(String typed, String word) {
        String w = properForm(word);
        if (typed.isEmpty()) return w;
        boolean firstUpper = Character.isUpperCase(typed.charAt(0));
        boolean allUpper = typed.length() > 1 && firstUpper;
        if (allUpper) {
            for (int i = 1; i < typed.length(); i++) {
                char c = typed.charAt(i);
                if (Character.isLetter(c) && !Character.isUpperCase(c)) {
                    allUpper = false;
                    break;
                }
            }
        }
        if (allUpper) return w.toUpperCase(locale);
        if (firstUpper && !w.isEmpty()) return w.substring(0, 1).toUpperCase(locale) + w.substring(1);
        return w;
    }

    /** Proper-noun capitalisation (Budapest, Budapesten, I, I'm, Monday…). */
    public String properForm(String lower) {
        String p = proper.get(lower);
        if (p != null) return p;
        if (!hungarian) {
            if (lower.equals("i") || lower.startsWith("i'")) return "I" + lower.substring(1);
            if (lower.endsWith("'s")) {
                String q = proper.get(lower.substring(0, lower.length() - 2));
                if (q != null) return q + "'s";
            }
            return lower;
        }
        for (int split = lower.length() - 1; split >= 3; split--) {
            String q = proper.get(lower.substring(0, split));
            if (q != null) {
                String rest = lower.substring(split);
                if (rest.startsWith("-")) rest = rest.substring(1);
                if (suffixChain(rest, 3)) return q + lower.substring(split);
                break;
            }
        }
        return lower;
    }

    // ---------------------------------------------------------------- main entry

    private static boolean skipWord(String typed) {
        if (typed.length() > 32) return true;
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (Character.isDigit(c) || c == '@' || c == '_' || c == '/' || c == '.' || c == '#') return true;
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(typed.charAt(i - 1))) return true; // iPhone, McDonald
        }
        return false;
    }

    private double score(Cand c, String prev) {
        double s = c.freq / 31.0 - 2.6 * c.cost;
        if (c.completion) s -= 0.7 + 0.12 * Math.max(0, c.word.length() - n);
        int uc = user.count(c.word);
        if (uc > 0) s += Math.min(1.5, 0.45 * Math.log(1 + uc) / Math.log(2));
        if (prev != null && !prev.isEmpty()) {
            int bc = user.bigram(prev, c.word);
            if (bc > 0) s += Math.min(1.4, 0.5 * bc);
        }
        return s;
    }

    /**
     * Analyses the word being typed.
     *
     * @param typed            the word as typed (any letter case)
     * @param prev             the previous word in lower case, or null
     * @param allowCorrection  false in fields where autocorrect makes no sense or for names
     */
    public synchronized Result analyze(String typed, String prev, boolean allowCorrection) {
        Result r = new Result();
        if (typed.isEmpty()) return r;
        String lower = typed.toLowerCase(locale).replace('’', '\'');
        boolean skip = skipWord(typed);
        if (skip) {
            r.typedValid = true;
            r.suggestions.add(typed);
            return r;
        }
        boolean dictValid = dict.freq(lower) > 0 || user.isKnown(lower);
        boolean morphValid = !dictValid && isValid(lower);
        r.typedValid = dictValid || morphValid;
        int len = lower.length();
        double mc = maxCost(len);
        HashMap<String, Cand> cands = search(lower, mc, true);
        addUserCandidates(lower, mc, true, cands);
        if (len >= 4) addSplit(lower, cands);

        ArrayList<Cand> list = new ArrayList<>(cands.values());
        for (Cand c : list) c.score = score(c, prev);
        list.sort((a, b) -> Double.compare(b.score, a.score));

        // ---- choose the correction
        Cand best = null;
        for (Cand c : list) {
            if (!c.completion && !c.word.equals(lower)) {
                best = c;
                break;
            }
        }
        String correction = null;
        if (dictValid) {
            // A real word was typed: only put back missing accents when the accented form is far more common.
            if (best != null && accentOnly(lower, best.word) && user.count(lower) < UserDict.KNOWN) {
                int ft = dict.freq(lower);
                if (ft > 0 && best.freq >= ft + 45 && best.score > score(new Cand(lower, 0, ft, false), prev) + 1.2) {
                    correction = best.word;
                }
            }
            // A common run-together pair ("nemtudom") becomes two words when both are far more common.
            if (correction == null && user.count(lower) < UserDict.KNOWN) {
                int ft = dict.freq(lower);
                for (Cand c : list) {
                    if (ft <= 105 && c.word.indexOf(' ') > 1 && c.word.indexOf(' ') < c.word.length() - 2 && c.freq >= ft + 40) {
                        correction = c.word;
                        break;
                    }
                }
            }
            // Rare entries of the word list are often typos themselves ("renben", "vagyk"):
            // replace them when a close word is ~100× more common.
            if (correction == null && best != null && user.count(lower) < UserDict.KNOWN) {
                int ft = dict.freq(lower);
                boolean dropsAccent = looseKey(lower).equals(looseKey(best.word)) && !accentsKept(lower, best.word);
                if (ft < 100 && best.cost <= 0.9 && best.freq >= ft + 60 && !dropsAccent) correction = best.word;
            }
        } else if (morphValid) {
            // Looks like a valid inflected/compound form; correct only to a very close, common word.
            if (best != null && best.cost <= 0.6 && best.freq >= 80) correction = best.word;
        } else {
            if (best != null && best.cost <= mc) correction = best.word;
            if (hungarian && (best == null || best.cost > 0.7)) {
                String restored = restoreAccents(lower);
                if (restored != null) correction = restored;
            }
        }
        if (correction != null && len <= 2 && !accentOnly(lower, correction)) correction = null;
        if (!allowCorrection) correction = null;

        // ---- suggestions: correction first, then the typed word (if valid), then the rest
        ArrayList<String> out = new ArrayList<>();
        if (correction != null) out.add(correction);
        if (r.typedValid && !out.contains(lower)) out.add(lower);
        for (Cand c : list) {
            if (out.size() >= 6) break;
            if (!out.contains(c.word)) out.add(c.word);
        }
        if (correction != null && out.size() > 1) {
            // keep the typed word available so the user can undo the correction with a tap
            if (!out.contains(lower)) out.add(1, lower);
        }
        for (String s : out) r.suggestions.add(applyCase(typed, s));
        r.correction = correction == null ? null : applyCase(typed, correction);
        return r;
    }

    /** Words likely to follow [prev] (learned bigrams first, then common fallbacks). */
    public List<String> predict(String prev, boolean sentenceStart, int max) {
        ArrayList<String> out = new ArrayList<>();
        if (prev != null && !prev.isEmpty()) {
            for (String w : user.next(prev, max)) out.add(properForm(w));
        }
        String[] fallback;
        if (sentenceStart) {
            fallback = hungarian ? new String[]{"Szia", "Én", "A", "Köszönöm", "Igen", "Nem"}
                    : new String[]{"I", "The", "Thanks", "Yes", "Hi", "It"};
        } else {
            fallback = hungarian ? new String[]{"és", "a", "hogy", "is", "nem", "meg"}
                    : new String[]{"the", "and", "to", "I", "a", "is"};
        }
        for (String f : fallback) {
            if (out.size() >= max) break;
            String w = sentenceStart && !f.isEmpty() ? f.substring(0, 1).toUpperCase(locale) + f.substring(1) : f;
            boolean dup = false;
            for (String o : out) if (o.equalsIgnoreCase(w)) dup = true;
            if (!dup) out.add(w);
        }
        if (sentenceStart) {
            for (int i = 0; i < out.size(); i++) {
                String w = out.get(i);
                out.set(i, w.substring(0, 1).toUpperCase(locale) + w.substring(1));
            }
        }
        return out;
    }

    /** Called when a word is committed, so the keyboard learns the user's vocabulary. */
    public void learn(String prevLower, String wordLower, boolean wasCorrected) {
        if (wordLower.isEmpty() || skipWord(wordLower)) return;
        boolean known = dict.freq(wordLower) > 0;
        if (known || !wasCorrected) user.addWord(wordLower, 1);
        if (prevLower != null && !prevLower.isEmpty()) user.addBigram(prevLower, wordLower);
    }

    /** The user undid an autocorrection: trust their word from now on. */
    public void learnRejected(String wordLower) {
        if (wordLower.isEmpty()) return;
        int c = user.count(wordLower);
        if (c < UserDict.KNOWN) user.addWord(wordLower, UserDict.KNOWN - c);
    }
}
