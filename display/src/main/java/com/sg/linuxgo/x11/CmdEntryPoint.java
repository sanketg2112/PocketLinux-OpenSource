package com.sg.linuxgo.x11;

import static android.system.Os.getuid;
import static android.system.Os.getenv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;

@Keep
@SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.sg.linuxgo.x11.CmdEntryPoint.ACTION_START";
    static Handler handler;
    public static Context ctx;
    public static boolean isEmbedded = true;
    private final Intent intent = createIntent();

    public static void main(String[] args) {
        Log.i("CmdEntryPoint", "commit " + BuildConfig.COMMIT);
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    /**
     * Re-invoke native X server start on the existing Looper thread.
     * main() never returns (Looper.loop), so after a crash X11Service cannot
     * call main() again — this is the recovery entry point.
     */
    public static void restartXServer(String[] args) {
        if (handler == null) {
            Log.e("CmdEntryPoint", "restartXServer: handler is null");
            return;
        }
        final String[] a = args != null ? args : new String[]{":0", "-ac", "-noreset"};
        handler.post(() -> {
            try {
                Log.i("CmdEntryPoint", "restartXServer: calling native start()");
                boolean ok = start(a);
                Log.i("CmdEntryPoint", "restartXServer: start() returned " + ok);
            } catch (Throwable t) {
                Log.e("CmdEntryPoint", "restartXServer failed", t);
            }
        });
    }

    CmdEntryPoint(String[] args) {
        boolean ok = false;
        try {
            ok = start(args);
            Log.i("CmdEntryPoint", "native start() returned " + ok);
            // Also write to redirected System.out (x11_server.log) when embedded
            System.out.println("CmdEntryPoint: native start() returned " + ok);
            System.out.flush();
        } catch (Throwable t) {
            Log.e("CmdEntryPoint", "native start() threw", t);
            System.out.println("CmdEntryPoint: native start() threw: " + t.getMessage());
            System.out.flush();
        }
        if (!ok) {
            Log.e("CmdEntryPoint", "X server failed to start — check XKB_CONFIG_ROOT / TMPDIR (see logcat LorieNative)");
            System.out.println("CmdEntryPoint: X server FAILED (no filesystem X0). Often Arch XKB absolute symlink or missing xkeyboard-config.");
            System.out.flush();
            if (!isEmbedded) System.exit(1);
            // Still broadcast so the activity can surface a clear error; socket will be missing.
        }

        spawnListeningThread();
        sendBroadcastDelayed();
    }

    @SuppressLint("WrongConstant")
    private Intent createIntent() {
        String targetPackage = getenv("LINUXGO_X11_OVERRIDE_PACKAGE");
        if (targetPackage == null || targetPackage.isBlank()) {
            targetPackage = BuildConfig.APPLICATION_ID;
        }

        Bundle bundle = new Bundle();
        bundle.putBinder(null, this);

        Intent launchIntent = new Intent(ACTION_START);
        launchIntent.putExtra(null, bundle);
        launchIntent.setPackage(targetPackage);

        if (getuid() == 0 || getuid() == 2000) {
            launchIntent.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);
        }

        return launchIntent;
    }

    private void sendBroadcast() {
        sendBroadcast(intent);
    }

    static void sendBroadcast(Intent intent) {
        try {
            Context context = ctx != null ? ctx : createContext();
            if (context == null) {
                throw new IllegalStateException("Context is null");
            }
            context.sendBroadcast(intent);
        } catch (Throwable e) {
            Log.e("Broadcast", "Failed to broadcast startup intent", e);
        }
    }

    private void sendBroadcastDelayed() {
        if (!connected()) {
            sendBroadcast(intent);
        }

        handler.postDelayed(this::sendBroadcastDelayed, 1000);
    }

    void spawnListeningThread() {
        new Thread(this::listenForConnections).start();
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        Context context;
        PrintStream err = System.err;
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int ignored) {}
            }));
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            if (System.getenv("OLD_CONTEXT") != null) {
                Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
                context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
            } else {
                Object activityThread = Class
                        .forName("sun.misc.Unsafe")
                        .getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, activityThreadClass);
                context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
            }
        } catch (Exception e) {
            Log.e("Context", "Failed to instantiate context:", e);
            context = null;
        } finally {
            System.setErr(err);
        }
        return context;
    }

    public static native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    private static native boolean connected();
    private native void listenForConnections();

    public static void loadNative(String tmpDir) {
        if (tmpDir != null) {
            try {
                android.system.Os.setenv("TMPDIR", tmpDir, true);
                android.system.Os.setenv("LINUXGO_X11_DEBUG", "1", true);
                Log.i("CmdEntryPoint", "Environment set: TMPDIR=" + tmpDir);
            } catch (Exception e) {
                Log.e("CmdEntryPoint", "Failed to set environment", e);
            }
        }

        try {
            Log.i("CmdEntryPoint", "Attempting System.loadLibrary(Xlorie)...");
            System.loadLibrary("Xlorie");
            Log.i("CmdEntryPoint", "System.loadLibrary(Xlorie) successful");
        } catch (Throwable t) {
            Log.e("CmdEntryPoint", "System.loadLibrary(Xlorie) failed", t);
            
            String path = "lib/" + Build.SUPPORTED_ABIS[0] + "/libXlorie.so";
            ClassLoader loader = CmdEntryPoint.class.getClassLoader();
            URL res = loader != null ? loader.getResource(path) : null;
            String libPath = res != null ? res.getFile().replace("file:", "") : null;
            
            if (libPath != null) {
                try {
                    Log.i("CmdEntryPoint", "Attempting System.load(" + libPath + ")...");
                    System.load(libPath);
                    Log.i("CmdEntryPoint", "System.load successful");
                } catch (Throwable t2) {
                    Log.e("CmdEntryPoint", "Fallback System.load failed", t2);
                }
            }
        }
    }

    static {
        try {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Something went wrong when preparing Looper", e);
        }
        handler = new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
        ctx = createContext();
    }
}
