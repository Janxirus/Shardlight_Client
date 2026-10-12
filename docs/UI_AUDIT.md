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
  4 px grid or the interface size setting (Parts 2 to 4, page by page).

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

- Library tiles: the Play and Open buttons sit at different heights because descriptions wrap to one or
  two lines.
- Large empty areas under short pages (Home, Discover while loading); no page width limit on wide windows.
- Project page header: chips wrap onto a second line at the default window size.
