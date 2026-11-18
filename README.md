# Glass Keyboard

Android billentyűzet az iOS 27 billentyűzet folyékony üveg (liquid glass) megjelenése alapján,
magyar és angol nyelvvel, magyarra hangolt automatikus javítással.

## Telepítés

1. A repó **Releases** részéből töltsd le a legfrissebb `GlassKeyboard-x.y.z.apk` fájlt és telepítsd.
2. Nyisd meg a **Glass Keyboard** alkalmazást, és kövesd a két lépést:
   bekapcsolás a rendszerben, majd kiválasztás.
3. Az alkalmazásban lévő mezőben azonnal ki is próbálhatod.

Minden feltöltés után a GitHub Actions lefordítja és kiadja az új APK-t (`.github/workflows/build.yml`).

## Funkciók

- **Automatikus javítás** szóköznél és írásjelnél: billentyűzet-szomszédság, felcserélt betűk,
  kimaradt/duplázott betűk, **hiányzó ékezetek** (ö/ő, ü/ű is), egybeírt szavak.
  Toldalékolt és összetett magyar szavakat felismeri, így nem „javítja el” őket.
  Visszatörléssel azonnal visszavonható, és onnantól megjegyzi a szót.
- **Javaslatsáv**: begépelt szó idézőjelben, a javítás kiemelve, alternatívák, következő szó, emoji.
- **Tanulás**: saját szavak és szópárok (következő szó javaslat), a beállításokban kezelhető.
- **Kiosztások**: magyar QWERTZ, angol QWERTY, 123 / #+= szimbólumok, szám- és telefonbillentyűzet,
  e-mail és webcím mezőkhöz külön alsó sor (@ . / .hu .com).
- **iOS-es apróságok**: billentyű-előnézet buborék, hosszú nyomásos ékezetválasztó,
  123-ról elhúzva egy szimbólum begépelése, szóköz trackpad (kurzormozgatás), dupla szóköz = pont,
  shift dupla koppintással Caps Lock, okos idézőjelek („ ”), mondat eleji nagybetű.
- **Emoji panel** kategóriákkal és gyakran használt emojikkal.
- **Mikrofon gomb**: átvált a telefon hangalapú bevitelére (pl. Google hangbevitel).
- Világos / sötét / rendszer téma, billentyűmagasság, rezgés, hang.

A billentyűzet nem kér internet-hozzáférést; minden a telefonon marad.

## Felépítés

| Fájl | Szerep |
|---|---|
| `Engine.java` | javítás és javaslatok: súlyozott, billentyűzetfüggő szerkesztési távolság a szótár-fán, ékezet-visszaállítás, magyar toldalék- és összetételfelismerés |
| `Dict.java` | memóriába leképezett bináris szótár (fa, gyakoriságokkal) |
| `UserDict.java` | tanult szavak és szópárok |
| `GlassKeyboardService.kt` | a billentyűzet szolgáltatás: szövegkezelés, javítás, shift, nyelvek |
| `KeyboardView.kt`, `GlassTheme.kt`, `Icons.kt`, `PopupOverlay.kt` | megjelenés és érintéskezelés |
| `CandidateBar.kt`, `EmojiView.kt`, `SettingsActivity.kt` | javaslatsáv, emoji panel, beállítások |
| `tools/build_dict.py` | a szótárak (`assets/hu.dict`, `assets/en.dict`) előállítása |

## Szótárak

A szógyakoriságok forrása a [wordfreq](https://github.com/rspeer/wordfreq) adatállománya és a
[FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles) lista, mindkettő CC BY-SA 4.0.
Magyar: ~377 000 szóalak, angol: 160 000 szó. Újraépítés: `tools/build_dict.py`.

## Verziózás

`fő.funkció.javítás` – 1.0.0-tól; az első szám nagy verzió, a második bármilyen új funkció/változás,
a harmadik csak hibajavítás. Egy szám növelésekor az utána következők nullázódnak.
