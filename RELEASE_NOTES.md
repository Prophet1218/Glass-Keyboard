## Glass Keyboard 0.0.16

- **Dictation rewritten** (`Dictation.kt`): tries Google's recognizer first (recording by itself, then fed with the keyboard's own recording), then the phone's offline recognizer, then the system default and the rest. Google's service is connected in advance when the keyboard opens, so the first press no longer hits a cold service. An empty result, silence or a recognizer that ignores the audio moves on to the next one, and every failure now shows a message (also in the suggestion bar)
- **Navigation bar area:** new setting – *Glass* (see-through like the keyboard background, default) or *Keyboard color* (solid). The keyboard also clears any background the system or the phone maker puts into the keyboard window's navigation bar views
- **Diagnostics** in the settings: a log of dictation attempts and of the keyboard window's layout, a dictation test that runs inside the settings, and a copy button
- **Settings keep their scroll position** when opened again
- **Phone language by default:** the settings and the default keyboard language follow the phone's language (Hungarian), otherwise English
- README, release notes and build workflow are now in English
