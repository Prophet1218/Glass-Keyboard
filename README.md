# Glass Keyboard

An Android keyboard modelled on the liquid glass look of the iOS 27 keyboard, with Hungarian and
English, and autocorrect tuned for Hungarian.

## Installation

1. Download the newest `GlassKeyboard-x.y.z.apk` from the repository's **Releases** and install it.
2. Open the **Glass Keyboard** app and follow the two steps: enable it in the system settings,
   then select it.
3. You can try it right away in the text field in the app.

After every push GitHub Actions builds and publishes the new APK (`.github/workflows/build.yml`).

## Features

- **Autocorrect** on space and punctuation: keyboard neighbours, swapped letters, missing or
  doubled letters, **missing accents** (also ö/ő, ü/ű), words typed together. It recognises
  inflected and compound Hungarian words, so it does not "fix" them. Backspace undoes a correction
  at once, and the word is remembered from then on.
- **Suggestion bar**: the typed word in quotes, the correction highlighted in a glass capsule,
  alternatives, next-word prediction, emoji.
- **Toolbar** while not typing: hide keyboard, emoji, GIF (KLIPY), clipboard, settings.
- **Learning**: your own words and word pairs (next-word prediction), manageable in the settings.
- **Layouts**: Hungarian QWERTZ, English QWERTY, 123 / #+= symbols, number and phone pads, special
  bottom row for e-mail and web address fields (@ . / .hu .com).
- **iOS details**: key preview balloon, long-press accent picker, slide from 123 to type one
  symbol, space bar trackpad (cursor movement), double space = period, double-tap shift for
  Caps Lock, smart quotes, auto-capitalisation.
- **Emoji panel** with categories and recently used emoji; **GIF search**; **clipboard history**.
- **Liquid glass**, **frosted glass** or solid style; light / dark / clear / system tone; glass
  colour, blur, refraction, dispersion and opacity can all be adjusted.
- **Backgrounds**: the glass sheet or one of 11 solid colours (black, graphite, greys, white,
  midnight blue, forest green, burgundy, plum, sand), picked from previews.
- Language switch: long press the emoji key.
- Optional number row and period key, adjustable key and bottom strip height, animations,
  vibration, sound.
- The app follows the phone's language (Hungarian, otherwise English).

## Structure

| File | Role |
|---|---|
| `Engine.java` | corrections and suggestions: weighted, keyboard-aware edit distance on the dictionary trie, accent restoration, Hungarian suffix and compound recognition |
| `Dict.java` | memory-mapped binary dictionary (trie with frequencies) |
| `UserDict.java` | learned words and word pairs |
| `GlassKeyboardService.kt` | the keyboard service: text handling, corrections, shift, languages |
| `KeyboardView.kt`, `GlassTheme.kt`, `LiquidLens.kt`, `Icons.kt`, `PopupOverlay.kt` | look and touch handling |
| `CandidateBar.kt`, `EmojiView.kt`, `GifPanel.kt`, `ClipboardPanel.kt` | suggestion bar / toolbar and panels |
| `SettingsActivity.kt`, `LiquidControls.kt`, `Ui.kt` | settings, liquid slider / switch, UI texts |
| `tools/build_dict.py` | builds the dictionaries (`assets/hu.dict`, `assets/en.dict`) |

## Dictionaries

Word frequencies come from the [wordfreq](https://github.com/rspeer/wordfreq) data and the
[FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles) lists, both CC BY-SA 4.0.
Hungarian: ~377,000 word forms, English: 160,000 words. Rebuild with `tools/build_dict.py`.

## Credits

The refraction model of the liquid glass lens (rounded-rectangle SDF, rim-band refraction,
dispersion) is based on Kyant's [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
(Apache License 2.0).

## Versioning

`major.feature.fix`, counting up from 0.0.2.
