## Glass Keyboard 0.0.18

- **Settings crash fixed:** every pop-up menu (language, navigation bar, glass options…) crashed – the frosted blur was set before the dialog's window existed
- **Dictation:** the log showed that *Speech Services by Google* starts but receives only silence, and that no recognizer accepts the keyboard's own recording (instant client error). The cause is that the phone treats the keyboard as a background app and does not let its recognizer use the microphone. Now, while dictating, a foreground service of type *microphone* runs (a small notification) so the app may use the microphone; if the recognizer still gets silence, an invisible screen of the app is opened and the same recognizer is tried again. Pointless retries after "client error" were removed
- **Navigation bar area:** back to fully see-through by default (the fading option stays in the settings)
