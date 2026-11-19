package com.example.glasskeyboard;

import java.nio.ByteBuffer;

/**
 * Read-only word trie stored in a compact binary file (see tools/build_dict.py).
 *
 * Layout: "GKD1", int nodeCount, then 8 bytes per node:
 * char (u16; top bit = the word ending here is accepted by the Hunspell spell checker) | frequency (u8, 0 = not a word end) | best frequency in subtree (u8) |
 * index of first child (u24) | child count (u8).
 * Children of a node are stored next to each other, ordered by their best subtree frequency.
 * The buffer is memory-mapped, so loading is instant and costs almost no heap.
 */
public final class Dict {
    private static final int HDR = 8;
    private static final int REC = 8;

    private final ByteBuffer b;
    public final int nodeCount;

    public Dict(ByteBuffer buffer) {
        this.b = buffer;
        if (b.get(0) != 'G' || b.get(1) != 'K' || b.get(2) != 'D' || b.get(3) != '1') {
            throw new IllegalArgumentException("Not a Glass Keyboard dictionary");
        }
        this.nodeCount = b.getInt(4);
    }

    public char ch(int n) {
        return (char) (b.getChar(HDR + n * REC) & 0x7FFF);
    }

    /** The word ending at node [n] is confirmed by the spell checker (Magyar Ispell). */
    public boolean verified(int n) {
        return (b.getChar(HDR + n * REC) & 0x8000) != 0;
    }

    /** The word is in the list and confirmed by the spell checker. */
    public boolean verified(CharSequence w) {
        int n = node(w);
        return n >= 0 && freq(n) > 0 && verified(n);
    }

    public int freq(int n) {
        return b.get(HDR + n * REC + 2) & 0xff;
    }

    public int maxSub(int n) {
        return b.get(HDR + n * REC + 3) & 0xff;
    }

    public int first(int n) {
        int o = HDR + n * REC + 4;
        return ((b.get(o) & 0xff) << 16) | ((b.get(o + 1) & 0xff) << 8) | (b.get(o + 2) & 0xff);
    }

    public int count(int n) {
        return b.get(HDR + n * REC + 7) & 0xff;
    }

    public int child(int n, char c) {
        int f = first(n);
        int e = f + count(n);
        for (int i = f; i < e; i++) {
            if (ch(i) == c) return i;
        }
        return -1;
    }

    /** Node reached by the word, or -1. */
    public int node(CharSequence w) {
        int n = 0;
        for (int i = 0; i < w.length() && n >= 0; i++) n = child(n, w.charAt(i));
        return n;
    }

    /** Frequency class of the word (1..255), 0 when it is not in the dictionary. */
    public int freq(CharSequence w) {
        int n = node(w);
        return n < 0 ? 0 : freq(n);
    }
}
