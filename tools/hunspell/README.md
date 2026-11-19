# Spell-checker validation of the Hungarian word list

The Hungarian dictionary is filtered and extended with **Magyar Ispell** (the Hunspell `hu_HU`
dictionary used by LibreOffice, https://github.com/LibreOffice/dictionaries/tree/master/hu_HU,
tri-licensed MPL 1.1 / GPL 2.0 / LGPL 2.1).

1. Build Hunspell (https://github.com/hunspell/hunspell) with CMake and compile `check.cpp`:

       g++ -O2 -std=c++17 -I hunspell/src/hunspell check.cpp hunspell/build/libhunspell-1.7.a -o check

2. Run every candidate word (all FrequencyWords `hu_full.txt` forms seen at least twice, the
   wordfreq words and the Hunspell headwords) through it and keep the accepted ones:

       ./check hu_HU.aff hu_HU.dic < candidates.txt | awk -F'\t' '$1=="1"{print $2}' | sort -u > hu_valid.txt

3. Build the dictionaries with the list:

       python3 build_dict.py <wordfreq/data> <hu_full.txt> <out dir> hu_valid.txt

Accepted forms are kept even when rare, rejected forms need to be frequent (slang, names,
loanwords), accent-less spellings of real words are dropped, and every accepted word is marked
in the trie (top bit of the character) so autocorrect leaves confirmed words alone.
