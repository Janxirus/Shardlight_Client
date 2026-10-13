# Speed and memory

Goals for the overhaul: the launcher shows an interactive Home in **under 2 seconds**, and idle memory is **no more than 15% higher** than the `launcher` branch before the overhaul. Nothing may animate while the window is minimised, and long lists must not build a row per item.

## How it is measured

`tools/perf/measure.sh` starts the launcher five times under a virtual display (xvfb, software rendering) with the real JVM flags and a fresh data folder each time. `StartupMeasure` (switched on with `-Dshardlight.measure=<file>`) writes:

- **startup to Home**: from JVM start until the first pulse after Home is on screen and the splash has closed,
- **heap** and **resident memory (RSS)** after 8 seconds of idle.

```
./gradlew :launcher-app:installDist
tools/perf/measure.sh launcher-app/build/install/shardlight-client 5
```

CI prints the same numbers in the run summary of every build (Linux runner). To compare with an older version, check it out in a second folder, add `StartupMeasure.java` and the two lines in `ShardlightApp` that call it, and run the script on both.

## Numbers (cloud container, Linux, xvfb, software rendering)

| Build | Startup to Home | Heap idle | RSS idle |
| --- | --- | --- | --- |
| `launcher` before the overhaul | 1.90 s | 30 MB | 244 MB |
| After Parts 3 to 7 | 2.01 s | not recorded | 275 MB |
| This part (Part 8) | 2.0 to 2.1 s | 55 MB | 283 MB |

Memory is about **16% higher** than before (goal 15%), and start is **about 2.0 s** (goal under 2 s). Both are on the line here, not clearly inside. Part 8 itself adds only about 3% (the new art, the splash and the focus and size rules); most of the growth comes from the pages and pictures that did not exist before (project covers, the Library grid, eight project tabs, the palette).

What was tried and gave nothing beyond noise: SerialGC (worse), `-Xms`, fewer compiler threads, and releasing the splash picture after it closes.

**Read these numbers as relative only.** Software rendering in a container is slower to draw than a real graphics card and uses more memory for the render surface. On a normal PC the same build should start faster and the gap in memory should be smaller, but only a measurement there can say so. The owner check is in [TESTING.md](TESTING.md).

## What keeps it light

- **Lists**: the Logs tab and the log analysis use a `ListView` (virtualised). The Content tab is a `TableView` (virtualised) that is at most 24 rows tall and scrolls inside, so a big modpack does not build a row with controls for every mod. Discover loads 20 results at a time ("Load more" fetches the next page). The Library grid builds one card per project and is not virtualised. If someone has hundreds of projects, this is the first place to look.
- **Animation**: `Motion.watchWindow` suspends every animation and looping effect while the window is minimised (iconified) and resumes when it is restored. Reduce motion in Settings turns the rest off.
- **Pictures**: art is loaded on first use and cached. The 2x files are used only on high-density screens or where a picture is shown large.
