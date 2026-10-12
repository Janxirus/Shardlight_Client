# UI audit (before the overhaul)

Taken from the 0.1.0 interface on the `launcher` branch, using `--capture` for every view in dark and
light. The before and after shots are in `docs/images/ui-overhaul/`. Items marked **fixed** were dealt
with in Part 1 (design system and base controls); the rest belong to the later parts of the overhaul.

![Before](images/ui-overhaul/part1-before.jpg)

## Colour

- **Fixed.** Colours were a flat list (`-sl-bg`, `-sl-bg-2`, `-sl-surface-2`, `-sl-surface-3`...) with no
  stated role, so the same grey meant "hover", "selected", "track" and "input" in different places.
- **Fixed.** Search and filter fields: the placeholder text was nearly invisible on the dark input
  background.
- **Fixed.** Light mode was dark mode with the greys swapped. Warning (`#f0b44c`) and success
  (`#47c98a`) text on white were about 2.0:1 and 2.1:1, far below the 4.5:1 WCAG AA minimum.
- **Fixed.** The accent only changed `-sl-accent` and its text colour; hover, pressed and selected
  states were `derive()` guesses. In light mode the default purple carried white text at about 4:1.
- **Fixed.** Status colours had one shade each, so banners and chips mixed `derive()` borders with
  full-strength text.
- **Fixed.** Hard-coded colours in CSS (`white` on danger buttons, the modal shadow, monospace font lists).

## Type

- **Fixed.** The interface font came from whatever the computer had (Segoe UI, SF Pro, Inter, Noto...),
  so spacing and line breaks differed between Windows, macOS and Linux.
- **Fixed.** Numbers (sizes, speeds, play time) used proportional digits and jiggled as they changed.
- **Fixed.** Sizes were ad hoc: 2, 1.6, 1.45, 1.35, 1.3, 1.12, 1.05, 0.9, 0.86, 0.82, 0.78, 0.76, 0.74 em.
- Several labels set `-fx-font-weight: bold` inline in Java instead of using a style class (Part 3).

## Spacing, shape and depth

- **Fixed.** Padding and gaps used about 25 different em values (0.22, 0.45, 0.62, 0.65, 0.7...) and
  corner radii 0.3 to 1.2 em with no rule.
- **Fixed.** Raised cards, modals and toasts had both a border and a drop shadow.
- Java layouts use fixed pixel gaps (`new HBox(10, ...)`, `new VBox(16, ...)`) that don't follow the
  4 px grid or the interface size setting. **Fixed in Part 2** for the window frame and every shared
  component (gaps live in the stylesheet); the pages themselves move over in Parts 3 and 4.

## States

- **Fixed.** Focus rings were drawn with `-fx-border-width: 1.6`, which shifted the content by a pixel.
- **Fixed.** No pressed state on most buttons, toggles or list rows; disabled was only opacity on buttons.
- **Fixed.** Check boxes, radio buttons, sliders, combo boxes, spinners and tabs had no hover or focus
  styling of their own; the colour picker, scroll-bar corners and table headers fell back to Modena.
- Lists show "Searching..." as plain text with no skeleton or empty state (Part 3).

## Icons

- **Fixed.** The set mixed solid shapes (Home, Library, Play) and outlined rings (Discover, Search,
  Info) with different visual weights and corner styles.
- **Fixed.** Missing icons for filter, sort, grid, list, favourite, time, CPU, memory, network, safety
  and settings sliders.

## Motion

- **Fixed.** Three hand-written animations (page fade 130 ms, modal fade 140 ms, switch 120 ms) with
  default easing, each checking Reduce motion on its own.

## Layout (later parts)

- **Fixed (Part 2).** Library tiles: the Play and Open buttons sat at different heights because descriptions
  wrapped to one or two lines. Tiles are now equal-size cells with one-line details and the buttons on the
  bottom edge.
- Large empty areas under short pages (Home, Discover while loading); no page width limit on wide windows.
- Project page header: chips wrap onto a second line at the default window size.

## Part 3: first run, Home, Library and the project page

- **Fixed.** Home had a news-style row and fixed-size tiles; it now has a hero for the selected project
  (art, version, loader, preset, modules, Play), a "Jump back in" row, and an alerts strip that appears only
  when something needs attention. Last played and play time come from real launch records only.
- **Fixed.** Library had one flat list; it now has grid and list views, sorting, filters, folders and drag to
  reorder, with its own empty and no-match states.
- **Fixed.** The project page showed everything in one scroll; it now has a header and eight tabs, each with
  loading, empty and error states.
- **Fixed.** Sign-in showed a code and little else; it now shows the waiting state, the player's head on
  success, and a plain next step for every error code, including "waiting for Minecraft approval" with Retry.
- **Left over.** The Settings and Backups tabs keep their earlier layouts (some fixed gaps); hover and drag
  states cannot be captured in a screenshot; the player head and sign-in were not checked against a real
  Microsoft account.

![Home](images/ui-overhaul/part3-home-dark.jpg)
![Library](images/ui-overhaul/part3-library-dark.jpg)
![Project overview](images/ui-overhaul/part3-project-overview-dark.jpg)
![Project mods](images/ui-overhaul/part3-project-content-dark.jpg)
![Performance](images/ui-overhaul/part3-project-performance-dark.jpg)
![First run](images/ui-overhaul/part3-firstrun-1-dark.jpg)
![Sign-in](images/ui-overhaul/part3-signin-code-dark.jpg)
![Home, light](images/ui-overhaul/part3-home-light.jpg)
![Library, light](images/ui-overhaul/part3-library-light.jpg)

## Part 4: Discover, Shardlight, Multiplayer, Downloads, Settings, wizard, dialogs

- **Fixed.** Discover now starts with the search box, a filter rail and result cards with an Install button
  that shows its state; the mod page has a gallery header, tabs, a version list and a dependency preview.
  While loading it shows skeletons; offline it keeps the last results and shows a message with Retry.
- **Fixed.** Shardlight shows four module cards with switches and 15 theme tiles; picking a theme applies it
  to the current project.
- **Fixed.** Multiplayer shows server cards with the MOTD in Minecraft colours, ping bars and a real player
  count (only what was fetched), and Join with the chosen project. The add-server dialog tests the ping live.
- **Fixed.** Downloads shows the active download with a ring, speed and ETA, pause, resume and cancel, a
  queue, collapsible history and errors in place with Retry. The badge and the top bar follow the page.
- **Fixed.** Settings has a left section list, a search that jumps to any setting, a live preview in
  Appearance, one-line descriptions, and changes that apply at once with an Undo toast.
- **Fixed.** The new-project wizard is three steps; loader, Java, memory and Shardlight sit in a collapsed
  Advanced part. The review lists exactly what will be downloaded, with the Minecraft size measured from
  what is missing on this computer.
- **Fixed.** Every dialog uses one frame (Cancel left, main action right, Esc closes, Enter confirms). The
  crash screen explains the likely cause in plain words and offers the fix, the log and a diagnostics zip.
- **Fixed (follow-up).** The Backups tab has a summary (count, space used, last backup), an icon per backup kind
  and a "Safety backup" chip; the install-plan dialog shows problems first, then one card per file with a
  Dependency chip; the log analysis uses the crash screen's layout (likely cause with its fix button, other
  findings below).
- **Left over.** Enter does not confirm a danger dialog, on purpose. Loader, add-on and Shardlight module
  sizes are not known before they download, so the review says so and does not add them to the total. The
  Environment filter works for Modrinth only. Hover and drag states cannot be captured in a screenshot,
  and nothing was checked against live Modrinth or Mojang servers (the cloud blocks them), so the
  screenshots use made-up example data.

![Discover](images/ui-overhaul/part4-discover.jpg)
![Mod page](images/ui-overhaul/part4-catalog.jpg)
![Discover offline](images/ui-overhaul/part4-discover-offline.jpg)
![Shardlight](images/ui-overhaul/part4-shardlight.jpg)
![Multiplayer](images/ui-overhaul/part4-multiplayer.jpg)
![Add server](images/ui-overhaul/part4-multiplayer-add.jpg)
![Downloads](images/ui-overhaul/part4-downloads.jpg)
![Downloads error](images/ui-overhaul/part4-downloads-error.jpg)
![Settings](images/ui-overhaul/part4-settings-appearance.jpg)
![Settings search](images/ui-overhaul/part4-settings-search.jpg)
![Wizard, template](images/ui-overhaul/part4-wizard-0.jpg)
![Wizard, basics](images/ui-overhaul/part4-wizard-1.jpg)
![Wizard, review](images/ui-overhaul/part4-wizard-2.jpg)
![Crash screen](images/ui-overhaul/part4-crash.jpg)
![Delete confirmation](images/ui-overhaul/part4-confirm-danger.jpg)
![Light: Discover](images/ui-overhaul/part4-discover-light.jpg)
![Light: Settings](images/ui-overhaul/part4-settings-appearance-light.jpg)
