## Glass Keyboard 1.3.3

- **Autocorrect now works in every app.** Fields that apps mark as "no suggestions" or "filter" (chats, social apps, search boxes, many WebView/Flutter/React Native apps) are corrected too – only password and e-mail address fields are left alone
- **Browser address bars:** search queries are corrected when you press space; web addresses (anything with `.`, `/`, `:` or `@`) are never touched
- Apps that report their text a little late no longer miss the correction (short retry)
- Apps that don't report their text at all fall back to what the keyboard typed, so correction still works there
