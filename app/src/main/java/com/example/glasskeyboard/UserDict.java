package com.example.glasskeyboard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the keyboard learned from the user, per language: words they use (with counts) and
 * which word tends to follow which (next-word prediction). Stored in a small text file.
 */
public final class UserDict {
    /** A word counts as "known" (never autocorrected away) from this many uses on. */
    public static final int KNOWN = 2;
    private static final int MAX_WORDS = 6000;
    private static final int MAX_BIGRAMS = 12000;

    private final File file;
    private final HashMap<String, Integer> words = new HashMap<>();
    private final HashMap<String, HashMap<String, Integer>> bigrams = new HashMap<>();
    private int bigramCount = 0;
    private boolean dirty = false;

    public UserDict(File file) {
        this.file = file;
        load();
    }

    public synchronized int count(String w) {
        Integer c = words.get(w);
        return c == null ? 0 : c;
    }

    public synchronized boolean isKnown(String w) {
        return count(w) >= KNOWN;
    }

    public synchronized void addWord(String w, int by) {
        if (w.isEmpty() || w.length() > 40) return;
        words.put(w, Math.min(10000, count(w) + by));
        dirty = true;
        if (words.size() > MAX_WORDS) trimWords();
    }

    public synchronized void addBigram(String prev, String w) {
        if (prev.isEmpty() || w.isEmpty()) return;
        HashMap<String, Integer> m = bigrams.get(prev);
        if (m == null) {
            m = new HashMap<>();
            bigrams.put(prev, m);
        }
        Integer c = m.get(w);
        if (c == null) bigramCount++;
        m.put(w, c == null ? 1 : Math.min(10000, c + 1));
        dirty = true;
        if (bigramCount > MAX_BIGRAMS) trimBigrams();
    }

    public synchronized int bigram(String prev, String w) {
        HashMap<String, Integer> m = bigrams.get(prev);
        if (m == null) return 0;
        Integer c = m.get(w);
        return c == null ? 0 : c;
    }

    /** Words that followed [prev], most frequent first. */
    public synchronized List<String> next(String prev, int max) {
        HashMap<String, Integer> m = bigrams.get(prev);
        if (m == null) return Collections.emptyList();
        ArrayList<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, c) -> c.getValue() - a.getValue());
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : l) {
            if (out.size() >= max) break;
            out.add(e.getKey());
        }
        return out;
    }

    /** All learned words with counts (for the settings screen). */
    public synchronized List<Map.Entry<String, Integer>> all() {
        ArrayList<Map.Entry<String, Integer>> l = new ArrayList<>(words.entrySet());
        l.sort((a, c) -> a.getKey().compareTo(c.getKey()));
        return l;
    }

    /** Snapshot of words starting with the given prefix (for completions). */
    public synchronized List<String> withPrefix(String prefix, int minCount) {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : words.entrySet()) {
            if (e.getValue() >= minCount && e.getKey().startsWith(prefix)) out.add(e.getKey());
        }
        return out;
    }

    public synchronized List<String> knownWords() {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : words.entrySet()) {
            if (e.getValue() >= KNOWN) out.add(e.getKey());
        }
        return out;
    }

    public synchronized void remove(String w) {
        if (words.remove(w) != null) dirty = true;
        HashMap<String, Integer> m = bigrams.remove(w);
        if (m != null) bigramCount -= m.size();
        for (HashMap<String, Integer> o : bigrams.values()) {
            if (o.remove(w) != null) bigramCount--;
        }
        dirty = true;
    }

    public synchronized void clear() {
        words.clear();
        bigrams.clear();
        bigramCount = 0;
        dirty = true;
    }

    private void trimWords() {
        ArrayList<Map.Entry<String, Integer>> l = new ArrayList<>(words.entrySet());
        l.sort((a, c) -> a.getValue() - c.getValue());
        for (int i = 0; i < l.size() / 5; i++) words.remove(l.get(i).getKey());
    }

    private void trimBigrams() {
        // Drop every pair seen only once; keeps the useful, repeated ones.
        bigramCount = 0;
        ArrayList<String> emptyKeys = new ArrayList<>();
        for (Map.Entry<String, HashMap<String, Integer>> e : bigrams.entrySet()) {
            e.getValue().values().removeIf(v -> v <= 1);
            bigramCount += e.getValue().size();
            if (e.getValue().isEmpty()) emptyKeys.add(e.getKey());
        }
        for (String k : emptyKeys) bigrams.remove(k);
    }

    private void load() {
        if (file == null || !file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                try {
                    if (p.length == 3 && p[0].equals("w")) {
                        words.put(p[1], Integer.parseInt(p[2]));
                    } else if (p.length == 4 && p[0].equals("b")) {
                        HashMap<String, Integer> m = bigrams.get(p[1]);
                        if (m == null) {
                            m = new HashMap<>();
                            bigrams.put(p[1], m);
                        }
                        m.put(p[2], Integer.parseInt(p[3]));
                        bigramCount++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Writes the file if anything changed. Safe to call from a background thread. */
    public void save() {
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            if (!dirty || file == null) return;
            dirty = false;
            for (Map.Entry<String, Integer> e : words.entrySet()) {
                sb.append("w\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            for (Map.Entry<String, HashMap<String, Integer>> e : bigrams.entrySet()) {
                for (Map.Entry<String, Integer> f : e.getValue().entrySet()) {
                    sb.append("b\t").append(e.getKey()).append('\t').append(f.getKey()).append('\t').append(f.getValue()).append('\n');
                }
            }
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }
}
