# Languages

Shardlight Client ships in English. Another language is a set of text files, so anyone can add one without
touching the code.

## Adding a language

1. Open **Settings > Appearance > Language > Add a language**. The launcher creates its languages folder
   (`languages` inside the data folder) with a short README and a `template/` folder holding every English file.
2. Copy `template/ui.properties` next to the template folder and rename the copy `ui_<language>.properties`:
   `ui_de.properties` for German, `ui_pt_BR.properties` for Brazilian Portuguese.
3. Translate the text after each `=`. Keep placeholders such as `{0}` and `{1}`, and write one apostrophe as two
   (`''`): the files are `MessageFormat` patterns. Save as **UTF-8**.
4. Add `language.name=Deutsch` to name the language in its own words. Without it the launcher uses the name Java
   knows for the language code.
5. The other template files (`common`, `install`, `auth`, `project`, `content`, `shardlight`, `skin`, `discord`)
   hold the messages that come from the launcher's core: copy each the same way
   (`common_de.properties`, ...) if you want those translated too.
6. Pick the language in Settings > Appearance and press **Restart now**. The language applies on restart.

A message a translation leaves out shows in English, so a file can start small and grow. Languages written right to
left (Arabic, Hebrew, Persian, Urdu) flip the whole layout on their own.

A translation can also be bundled with the launcher: put the files in `launcher-core/src/main/resources/i18n/` (and
`launcher-app/src/main/resources/i18n/` for `ui_<lang>.properties`) and they appear in the picker without the
languages folder. English text lives in `ui.properties` and the five core bundles; every string the launcher shows is a
key there. A test fails if the source uses a key that does not exist or a value is not a valid pattern.

`--lang <tag>` on the command line (for example `--lang de`) starts the launcher in that language once, without
changing the setting.

## Test languages

Two made-up languages test the interface without a translator. They are hidden from the picker unless turned on by
the `SHARDLIGHT_TEST_LANGUAGES` environment variable or `-Dshardlight.testLanguages=true`; `--lang` always works.

| Tag | What it does |
|---|---|
| `en-XA` | Every message becomes accented and about a third longer, inside brackets: "Play" is `[Þláý ~~]`. Text that is clipped, cannot grow or was never put in a bundle shows up straight away: it stays plain English, or loses its closing bracket. |
| `ar-XB` | The same text, and the layout flips as it does for a right-to-left language. |

Arguments (`{0}`), quoted text and the words inside `choice` formats are kept intact, so the result is still a valid
pattern.

To see a view in a test language: `./gradlew :launcher-app:run --args="--lang en-XA"`. To save screenshots of every
view (`--capture`, see TESTING.md), add the same flag.

## What was checked

All main views were captured in `en-XA` (and Home and the project page in `ar-XB`), dark and light, and fixed where
they clipped: the project tab row now keeps every tab whole and scrolls when the window is too narrow, the Discover
filter segments wrap, stat cards grow to their text, the Settings menu widens, the page title never shrinks for the
page's own buttons, and the Discover categories are translated. The one string that stays English on purpose is the
"Shardlight Client couldn't start" window, which appears when the launcher cannot even load its text.
