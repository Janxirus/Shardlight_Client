package dev.shardlight.client.app.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows-only touches on the native window: a title bar that follows the theme (dark mode on Windows 10 and
 * 11, the caption and caption-text colours on Windows 11) and progress on the taskbar button. Everything here
 * is a no-op on other systems, and any failure turns the feature off for the session instead of affecting the
 * app.
 */
public final class WindowsChrome {
    private static final Logger LOG = Logger.getLogger(WindowsChrome.class.getName());
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static boolean broken;
    private static Taskbar taskbar;
    private static boolean taskbarTried;

    /** taskbar progress states */
    public enum Progress {
        NONE(0), INDETERMINATE(0x1), NORMAL(0x2), ERROR(0x4), PAUSED(0x8);

        final int flag;

        Progress(int flag) {
            this.flag = flag;
        }
    }

    private WindowsChrome() {
    }

    public static boolean supported() {
        return WINDOWS && !broken;
    }

    /** colour the title bar of {@code stage}: dark or light frame, and on Windows 11 the caption colours */
    public static void titleBar(Stage stage, boolean dark, Color caption, Color text) {
        if (!supported() || !stage.isShowing()) {
            return;
        }
        try {
            WinDef.HWND hwnd = Native32.window(stage);
            if (hwnd == null) {
                return;
            }
            IntByReference on = new IntByReference(dark ? 1 : 0);
            // 20 on Windows 10 20H1 and later, 19 on the builds before it
            if (Native32.DWM.DwmSetWindowAttribute(hwnd, 20, on, 4) != 0) {
                Native32.DWM.DwmSetWindowAttribute(hwnd, 19, on, 4);
            }
            // Windows 11 only; older versions reject these, which is fine
            Native32.DWM.DwmSetWindowAttribute(hwnd, 35, new IntByReference(colorRef(caption)), 4);
            Native32.DWM.DwmSetWindowAttribute(hwnd, 36, new IntByReference(colorRef(text)), 4);
        } catch (Throwable t) {
            fail("title bar colours", t);
        }
    }

    /** show {@code fraction} (0..1) on the taskbar button, or another state; NONE clears it */
    public static void taskbar(Stage stage, Progress state, double fraction) {
        if (!supported() || stage == null || !stage.isShowing()) {
            return;
        }
        try {
            WinDef.HWND hwnd = Native32.window(stage);
            Taskbar t = taskbar();
            if (hwnd == null || t == null) {
                return;
            }
            t.setProgressState(hwnd, state.flag);
            if (state == Progress.NORMAL || state == Progress.ERROR || state == Progress.PAUSED) {
                t.setProgressValue(hwnd, Math.round(Math.max(0, Math.min(1, fraction)) * 1000), 1000);
            }
        } catch (Throwable e) {
            fail("taskbar progress", e);
        }
    }

    /** Windows COLORREF: 0x00BBGGRR */
    static int colorRef(Color c) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return (b << 16) | (g << 8) | r;
    }

    private static Taskbar taskbar() {
        if (!taskbarTried) {
            taskbarTried = true;
            taskbar = Taskbar.create();
        }
        return taskbar;
    }

    private static void fail(String what, Throwable t) {
        broken = true;
        LOG.log(Level.INFO, "Windows " + what + " are off for this session", t);
    }

    /** the native calls; only loaded on Windows */
    private static final class Native32 {
        static final Dwm DWM = Native.load("dwmapi", Dwm.class);

        interface Dwm extends StdCallLibrary {
            int DwmSetWindowAttribute(WinDef.HWND hwnd, int attribute, IntByReference value, int size);
        }

        /** this process's visible top-level window whose title is the stage's title */
        static WinDef.HWND window(Stage stage) {
            Object cached = stage.getProperties().get(Native32.class);
            if (cached instanceof WinDef.HWND h && User32.INSTANCE.IsWindow(h)) {
                return h;
            }
            int pid = (int) ProcessHandle.current().pid();
            String title = stage.getTitle() == null ? "" : stage.getTitle();
            WinDef.HWND[] found = new WinDef.HWND[1];
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
                if (owner.getValue() != pid || !User32.INSTANCE.IsWindowVisible(hwnd)) {
                    return true;
                }
                char[] buf = new char[512];
                int n = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
                if (new String(buf, 0, Math.max(0, n)).equals(title)) {
                    found[0] = hwnd;
                    return false;
                }
                return true;
            }, null);
            if (found[0] != null) {
                stage.getProperties().put(Native32.class, found[0]);
            }
            return found[0];
        }
    }

    /** ITaskbarList3 through its COM vtable */
    private static final class Taskbar extends Unknown {
        private static final Guid.CLSID CLSID_TASKBAR_LIST = new Guid.CLSID("{56FDF344-FD6D-11d0-958A-006097C9A090}");
        private static final Guid.IID IID_TASKBAR_LIST3 = new Guid.IID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}");
        private static final int CLSCTX_INPROC_SERVER = 0x1;
        // IUnknown (0-2), ITaskbarList: HrInit 3, AddTab 4, DeleteTab 5, ActivateTab 6, SetActiveAlt 7,
        // ITaskbarList2: MarkFullscreenWindow 8, ITaskbarList3: SetProgressValue 9, SetProgressState 10
        private static final int HR_INIT = 3;
        private static final int SET_PROGRESS_VALUE = 9;
        private static final int SET_PROGRESS_STATE = 10;

        private Taskbar(Pointer p) {
            super(p);
        }

        static Taskbar create() {
            try {
                Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
                PointerByReference out = new PointerByReference();
                WinNT.HRESULT hr = Ole32.INSTANCE.CoCreateInstance(CLSID_TASKBAR_LIST, null, CLSCTX_INPROC_SERVER,
                    IID_TASKBAR_LIST3, out);
                if (hr.intValue() != 0 || out.getValue() == null) {
                    return null;
                }
                Taskbar t = new Taskbar(out.getValue());
                t._invokeNativeInt(HR_INIT, new Object[] {t.getPointer()});
                return t;
            } catch (Throwable e) {
                LOG.log(Level.INFO, "No taskbar progress", e);
                return null;
            }
        }

        void setProgressValue(WinDef.HWND hwnd, long done, long total) {
            _invokeNativeInt(SET_PROGRESS_VALUE, new Object[] {getPointer(), hwnd, done, total});
        }

        void setProgressState(WinDef.HWND hwnd, int flags) {
            _invokeNativeInt(SET_PROGRESS_STATE, new Object[] {getPointer(), hwnd, flags});
        }
    }
}
