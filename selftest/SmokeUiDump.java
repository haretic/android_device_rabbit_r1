import java.io.File;
import java.lang.reflect.Method;

/** A fresh accessibility snapshot, without waiting for continuously updating meters to idle. */
public final class SmokeUiDump {
    public static void main(String[] args) {
        try {
            dump(args);
        } catch (Throwable failure) {
            // app_process otherwise reports this shell-tool failure as an AndroidRuntime
            // fatal exception. Never publish a partial hierarchy, including on disconnect.
            try {
                if (args != null && args.length == 1) new File(args[0]).delete();
            } catch (Throwable ignored) { }
            try {
                Throwable cause = failure;
                for (int i = 0; i < 8 && cause.getCause() != null
                        && cause.getCause() != cause; i++) cause = cause.getCause();
                System.err.println("SmokeUiDump: no dump (" + cause.getClass().getSimpleName() + ")");
            } catch (Throwable ignored) { }
            System.exit(1);
        }
    }

    private static void dump(String[] args) throws Exception {
        if (args == null || args.length != 1) throw new IllegalArgumentException("One output file required");
        File file = new File(args[0]);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot remove old dump");
        Class<?> wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = wrapperClass.getConstructor().newInstance();
        try {
            wrapperClass.getMethod("connect").invoke(wrapper);
            Object automation = wrapperClass.getMethod("getUiAutomation").invoke(wrapper);
            Object root = null;
            for (int i = 0; i < 30 && root == null; i++) {
                Thread.sleep(100);
                root = Class.forName("android.app.UiAutomation")
                        .getMethod("getRootInActiveWindow").invoke(automation);
            }
            if (root == null) throw new IllegalStateException("No active window root");
            Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = globalClass.getMethod("getInstance").invoke(null);
            Object display = globalClass.getMethod("getRealDisplay", int.class).invoke(global, 0);
            Class<?> displayClass = Class.forName("android.view.Display");
            Class<?> pointClass = Class.forName("android.graphics.Point");
            Object size = pointClass.getConstructor().newInstance();
            displayClass.getMethod("getRealSize", pointClass).invoke(display, size);
            int rotation = (Integer) displayClass.getMethod("getRotation").invoke(display);
            for (Method method : Class.forName("com.android.uiautomator.core.AccessibilityNodeInfoDumper").getMethods()) {
                if (method.getName().equals("dumpWindowToFile") && method.getParameterTypes().length == 5) {
                    method.invoke(null, root, file, rotation,
                            pointClass.getField("x").getInt(size), pointClass.getField("y").getInt(size));
                    if (!file.isFile() || file.length() == 0) throw new IllegalStateException("No dump written");
                    return;
                }
            }
            throw new IllegalStateException("No supported dumper method");
        } finally {
            wrapperClass.getMethod("disconnect").invoke(wrapper);
        }
    }
}
