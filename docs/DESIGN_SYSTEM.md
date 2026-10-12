# Shardlight Client design system

How the launcher looks and how to style anything new. The code lives in `launcher-app`:

| What | Where |
| --- | --- |
| Colour tokens, type, spacing, shapes, every control | `src/main/resources/app/shardlight.css` |
| Accent ramp and contrast maths | `ui/DesignTokens.java` |
| Theme switching (dark, light, high contrast, compact, accent, size) | `ui/ThemeManager.java` |
| Fonts | `ui/Fonts.java`, `src/main/resources/app/fonts/` |
| Animation | `ui/Motion.java` (Reduce motion flag in `ui/UiSettings.java`) |
| Icons | `ui/Icons.java` |
| Shared widgets (buttons, cards, chips, banners, setting rows) | `ui/UiKit.java`, `ui/ToggleSwitch.java`, `ui/Overlay.java` |
| Contrast test | `src/test/java/.../ui/DesignTokensContrastTest.java` |

![After, dark](images/ui-overhaul/part1-after-dark.jpg)
![After, light](images/ui-overhaul/part1-after-light.jpg)

## Rules

1. Use tokens and style classes, never raw colours or sizes in Java. If something new needs a colour,
   add a token to `.root` and `.root.light` in the stylesheet and a pair to the contrast test.
2. Sizes are in `em` so the interface size setting scales them (1 em = 14 px at 100%).
3. Spacing and sizes sit on a 4 px grid. Corner radii come from the shape scale.
4. An element has a border or a shadow, never both.
5. Every interactive thing has hover, pressed, focused (`:focus-visible`) and disabled states.
6. Only opacity, translation and scale are animated, always through `Motion`.
7. Every visible string is a key in `src/main/resources/i18n/ui.properties`.

## Colour tokens

Tokens are JavaFX looked-up colours defined on `.root` (dark, the default) and `.root.light`. Light is
designed on its own, not as an inverted dark: cool greys, white cards, deeper status colours.

### Surfaces (five levels)

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| `-sl-sunken` | `#0a0c11` | `#e9ebf1` | Wells: sidebar, code blocks, segmented controls, table headers |
| `-sl-bg` | `#0e1016` | `#f3f4f8` | The window background |
| `-sl-surface` | `#151922` | `#ffffff` | Cards, tiles, lists |
| `-sl-raised` | `#1b202c` | `#f7f8fb` | Controls (buttons, combo boxes), hovered cards, the selected nav item |
| `-sl-overlay-bg` | `#222838` | `#ffffff` | Floating things: modals, menus, popups, toasts |

Fills and overlays for states: `-sl-fill` (hover fill, icon boxes), `-sl-fill-strong` (pressed fill,
tracks), `-sl-field` (inside text fields and check boxes), `-sl-hover` and `-sl-pressed` (translucent, so
they work on any surface).

### Text (three levels) and borders (two levels)

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| `-sl-text` | `#eceef5` | `#141722` | Primary text |
| `-sl-text-2` | `#aeb4c8` | `#474d63` | Secondary text, default icons (`.muted`) |
| `-sl-text-3` | `#8b92a9` | `#5f667d` | Captions, placeholders, hints (`.faint`) |
| `-sl-border` | `#262c3c` | `#dde0e8` | Card and divider lines |
| `-sl-border-strong` | `#3b4359` | `#b8bfcf` | Control outlines, hovered cards |

All three text levels reach 4.5:1 on all five surfaces in both themes.

### Accent ramp

Computed by `DesignTokens.ramp(accent, light)` from the accent setting and set inline on the root by
`ThemeManager.apply`, so changing the accent recolours every one of these at once. The stylesheet holds
the values for the default purple (`#7C6CF2`).

| Token | Use |
| --- | --- |
| `-sl-accent-subtle` | Tinted background: selected rows, accent chips, selected toggles, the hero gradient |
| `-sl-accent-hover` | Accent fill under the pointer |
| `-sl-accent` | Accent fill: primary buttons, switches, checked boxes, progress |
| `-sl-accent-pressed` | Accent fill while pressed |
| `-sl-accent-text` | Text and icons on the three accent fills |
| `-sl-accent-fg` | Accent-coloured text, links and icons on surfaces; also the focus ring (`-sl-focus`) |

How it is built: the chosen colour is kept if it already works; otherwise its HSL lightness moves the
shortest distance until text on it reaches 4.5:1 (dark text on a lighter fill or white text on a darker
one) and the fill reaches 3:1 against the surfaces. Hover and pressed move further away from the text
colour, so they never lose contrast. `-sl-accent-fg` is moved until it reaches 4.5:1 on every surface.
`-sl-accent-2` (teal `#46d3c4`) is the fixed second brand colour used only in the Play button and
progress gradients.

### Status

Each has a strong variant (text, icons, fills) and a subtle one (backgrounds). Strong text reaches 4.5:1
on every surface and on its own subtle background.

| Token | Dark | Light |
| --- | --- | --- |
| `-sl-success` / `-sl-success-subtle` | `#4ad295` / `#11281f` | `#157a4a` / `#e2f4ea` |
| `-sl-warning` / `-sl-warning-subtle` | `#f2b84f` / `#2b2312` | `#8a5a00` / `#fbf0d9` |
| `-sl-danger` / `-sl-danger-subtle` | `#ff7b84` / `#33181c` | `#c0303d` / `#fde7e9` |
| `-sl-info` / `-sl-info-subtle` | `#6cb4ff` / `#13243b` | `#1f66c2` / `#e4eefb` |

Danger buttons use `-sl-on-danger` text and `-sl-danger-hover`.

### Small pieces and old names

`-sl-tooltip-bg` / `-sl-tooltip-text`, `-sl-knob` and `-sl-switch-off` (switches), `-sl-icon` (see
Icons), `-sl-focus`. The names from before the design system still work: `-sl-bg-2` = `-sl-sunken`,
`-sl-surface-2` = `-sl-raised`, `-sl-surface-3` = `-sl-fill`, `-sl-shadow` = `-sl-shadow-2`,
`-sl-overlay` (= `-sl-scrim`) is the dimmed backdrop behind modals.

### High contrast

`.root.high-contrast` makes every text level primary text, makes borders clearly visible, and gives
floating surfaces (modals, menus, toasts, tooltips) a border instead of a shadow.

### Contrast test

`DesignTokensContrastTest` reads the stylesheet and checks, in dark, light and both high-contrast
variants: every text level and status colour on every surface (4.5:1), text on fills and subtle
backgrounds, text on danger and tooltips, the switch knob on its track (3:1), and the accent ramp for all
eight presets plus 200 random colours. It also checks that the stylesheet's default ramp matches what
`DesignTokens` computes. Add a pair whenever you add a token that text sits on.

## Type

Fonts are bundled and loaded at startup by `Fonts.load()`; if one fails the matching system font is
used. **Inter** for the interface, **Inter Tabular** (Inter with tabular figures, so digits line up and
don't jiggle) for numbers, **JetBrains Mono** for logs and code. JavaFX only asks for regular or bold, so
the bold slot holds Inter's SemiBold (600): `-fx-font-weight: bold` renders semibold.

| Class | Size | At 100% | Weight | Use |
| --- | --- | --- | --- | --- |
| `.display` | 2.286em | 32 px | semibold | Hero titles |
| `.h1`, `.page-title` | 1.714em | 24 px | semibold | Page titles |
| `.h2`, `.modal-title` | 1.429em | 20 px | semibold | Section titles, dialog titles |
| `.h3` | 1.143em | 16 px | semibold | Card titles, group headings |
| `.body` (default) | 1em | 14 px | regular | Everything else |
| `.small` | 0.857em | 12 px | regular | Secondary lines, chips, tooltips |
| `.caption` (`.tiny`) | 0.786em | 11 px | regular | Labels under numbers, eyebrows |
| `.mono` | 0.929em | 13 px | regular | Paths, versions, logs (`.code-block`, `.log-view`) |

Use `.tabular` on any label showing a size, speed, count or time (`.stat-value`, `.progress-text` and
`.table-cell.numeric` get it automatically). `.strong` makes text semibold; `.muted` and `.faint` pick
the second and third text levels.

## Spacing

A 4 px grid. Use these steps only (em at 100%):

| px | 4 | 8 | 12 | 16 | 24 | 32 | 48 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| em | 0.286 | 0.571 | 0.857 | 1.143 | 1.714 | 2.286 | 3.429 |

Cards pad 16, pages pad 24 at the sides, items in a card are 12 apart, sections on a page 16 apart.
Java gaps (`new HBox(8, ...)`) should use the px column until those layouts move to CSS.

## Shape

| Radius | px | em | For |
| --- | --- | --- | --- |
| Small | 6 | 0.429 | Text fields, combo boxes, chips, tooltips, menu items |
| Medium | 10 | 0.714 | Buttons, nav items, banners, menus, segmented controls |
| Large | 14 | 1 | Cards, tiles, lists, toasts, the hero |
| Extra large | 20 | 1.429 | Modals |

Switches, sliders, progress bars and search fields are fully rounded.

## Depth

Three shadow levels, as classes and as the colours `-sl-shadow-1/2/3`:

| Class | For |
| --- | --- |
| `.elevation-1` | Selected segment, slider thumbs, tooltips |
| `.elevation-2` | Raised cards (`.card.raised`), menus, popups, toasts |
| `.elevation-3` | Modals |

Never a border and a shadow on one element: `.card.raised` drops its border. With Background effects off
(`.root.no-effects`) shadows are removed and raised cards get their border back.

## Motion

Every animation goes through `ui/Motion.java`.

| Speed | Duration | For |
| --- | --- | --- |
| `Motion.Speed.FAST` | 120 ms | Hover and press, switch knobs |
| `Motion.Speed.MEDIUM` | 200 ms | Panels, popovers, toasts, the modal backdrop |
| `Motion.Speed.SLOW` | 320 ms | Pages and modals |

`Motion.ENTER` is ease-out (things appearing), `Motion.EXIT` is ease-in (things leaving). Helpers:
`enter(node, speed, dy)` fades in while rising `dy` px (pages and toasts use `Motion.ENTER_OFFSET`, 8 px),
`fadeIn`, `exit(node, speed, done)`, `pop(node, speed, from)` (modals grow from 0.96), `moveX`. With Reduce
motion on, moves and scales are instant and fades take 80 ms. Never use `FadeTransition` and friends
directly.

## Icons

`Icons.of(Icons.NAME, size)` returns a node (the app uses sizes such as 13, 16, 18 and 40). All icons are drawn on a
24-unit grid as outlines with a 2-unit stroke and round caps and joins, inside 2.5 to 21.5, with corner
radii of 1 to 2 units. Play, Stop and More are also filled. A clear 24x24 square keeps every glyph on the
same grid, so icons line up with each other.

The colour comes from the looked-up colour `-sl-icon`, which any ancestor can set; the nearest one wins:

```css
.button.primary { -sl-icon: -sl-accent-text; }
.banner.warning { -sl-icon: -sl-warning; }
```

Helper classes: `.accent-icon`, `.success-icon`, `.warning-icon`, `.danger-icon`, `.info-icon`, `.muted`,
`.faint`. To add an icon, draw it on the same grid with the same stroke, add the constant to `Icons`, and
add it to `SOLID` only if it must be filled.

Set: HOME, LIBRARY, DISCOVER, SHARD, SERVERS, DOWNLOAD, GEAR, PLAY, STOP, PLUS, SEARCH, FILTER, SORT, GRID,
LIST, SLIDERS, FOLDER, TRASH, MORE, CLOSE, BACK, REFRESH, EXTERNAL, COPY, PIN, CHECK, WARNING, INFO,
ERROR, UPDATE, CLOCK, SHIELD, BOLT, USER, PACKAGE, CUBE, IMAGE, ARCHIVE, PALETTE, GAUGE, SPARKLE, LOGS,
STAR, HEART, EYE_OFF, KEY, WORLD, CPU, MEMORY, WIFI.

## Controls

Controls draw their outline and focus ring with background layers (`-fx-background-color: outline,
fill; -fx-background-insets: 0, 1`), so focusing never moves anything. The focus ring is 2 px of
`-sl-focus` and shows on keyboard focus only (`:focus-visible`); text fields show it whenever focused.

| Control | How to get it | Notes |
| --- | --- | --- |
| Secondary button | `UiKit.button(...)` or a plain `Button` | Raised fill, strong outline |
| Primary button | `UiKit.primary(...)`, class `primary` | One per view |
| Ghost button | class `ghost` | Toolbars, rows |
| Danger button | class `danger` | Destructive confirmations only |
| Icon-only button | `UiKit.iconButton(icon, name, action)` | Ghost, with tooltip and accessible name |
| Sizes | classes `small`, `large` | |
| Text field | `TextField`; `search-field` for pill search; `error` for invalid input | |
| Combo, choice, spinner, colour picker | the JavaFX control | Popups float on `-sl-overlay-bg` |
| Check box, radio button | the JavaFX control | Accent fill when checked |
| Switch | `new ToggleSwitch(value)` | Use inside `UiKit.settingRow` |
| Slider | `Slider` | Accent thumb |
| Tabs | `TabPane` | Underline in `-sl-accent-fg` |
| Segmented | `UiKit.segmented(...)` | For 2 to 4 options |
| Lists and tables | `ListView`, `TableView` | Selected row uses `-sl-accent-subtle` |
| Chips | `UiKit.chip(text, variant)` | Variants: `accent`, `success`, `warning`, `danger`, `info` |
| Banners | `UiKit.banner(kind, text)` | Subtle status background, strong icon |
| Tooltips, context menus | `UiKit.tooltip`, `ContextMenu` | Menu items can add class `danger` |

## Styling a new view

1. Build it from `UiKit` pieces and JavaFX controls; give containers style classes (`card`, `page`,
   `banner`, your own class) instead of `setStyle`.
2. If it needs its own rules, add a short section near the end of `shardlight.css` (page-specific
   pieces go after the base controls so they win), using only tokens, the spacing steps and the radius
   scale.
3. New colours: add the token to `.root` and `.root.light` (and `.root.high-contrast` if text levels are
   involved), then add the text/background pair to `DesignTokensContrastTest`.
4. Numbers get `.tabular`; paths and versions get `.mono`.
5. Animate with `Motion` only.
6. Check it with `--capture` in dark and light, at 100% and a larger interface size, with high contrast
   and Reduce motion on, and with the keyboard alone.
