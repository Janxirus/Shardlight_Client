# Keyboard and screen readers

What Shardlight Client promises, and the check that keeps it true.

## Promises

- **Everything works with the keyboard alone.** Tab and Shift+Tab move between controls, Enter or Space press
  them, Escape closes a dialog or popover, Ctrl+K opens the command palette, Ctrl+F searches the page, Alt+Left
  and Alt+Right go back and forward. Cards that open something take Enter and Space like buttons.
- **The focus ring is always visible** on keyboard focus: 2 px of the accent colour on every control, the same in
  dark, light and high contrast (`:focus-visible`, see DESIGN_SYSTEM.md).
- **No keyboard traps.** A dialog keeps Tab inside itself until it closes, which is what a dialog should do, and
  Escape always closes it. A multi-line box (the project notes) uses Tab to leave, like every other field.
- **Icon-only buttons have a name and a tooltip.** `UiKit.iconButton(icon, name, action)` sets both from one
  string; the name is what a screen reader reads.
- **Click targets are at least 32 x 32 px** at every interface size (`min-height` / `min-width` in the
  stylesheet, "Click targets").
- **Contrast.** Every text and status colour reaches 4.5:1 on every surface in dark, light and high contrast
  (`DesignTokensContrastTest`).
- **Motion.** Reduce motion makes moves instant; nothing animates while the window is minimised.

## The check

`A11yAudit` looks at every view that `--capture` shows:

1. an icon-only button without accessible text or without a tooltip,
2. a button or link smaller than 32 x 32 px,
3. something that reacts to a mouse click but cannot take keyboard focus (the dimmed layer behind a dialog is
   exempt, because Escape closes the dialog; so is padding around a real button),
4. with `-Dshardlight.tabWalk=true`, a Tab walk from the current focus: focus that gets stuck on one control
   while other controls exist, or a view with nothing the keyboard can reach.

```
./gradlew :launcher-app:run --args="--capture build/shots --capture-matrix"
cat build/shots/accessibility-report.txt
```

CI runs it on every build and uploads the report with the screenshots. Add `-Dshardlight.auditStrict=true` to make
the run exit with an error when there is any finding.

## Not covered by the check (do by hand)

- That a screen reader (Narrator, VoiceOver, Orca) reads each view in a sensible order. JavaFX exposes the
  accessible text and roles; the order of reading was not tested with a real screen reader.
- The 3D skin viewer has keyboard control (arrow keys) but no spoken description beyond its label.
