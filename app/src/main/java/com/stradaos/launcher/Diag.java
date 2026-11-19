package com.stradaos.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/** Exporta las apps de audio/Bluetooth de fábrica para analizar cómo comparten la pista. */
public class Diag {

    static final String[] KEYS = {"bt", "blue", "music", "audio", "media", "radio", "mcu", "car", "avrcp",
            "a2dp", "goc", "syu", "can", "phone", "dial", "aux", "service", "launcher", "setting", "auto", "nav",
            "conn", "usb", "easy", "carbit", "link"};

    /**
     * Devuelve la carpeta donde quedó todo (o un mensaje de error). player = estado del reproductor y eventos recientes
     * (Main.playerDiagText). Nunca se guardan los valores de los ajustes: pueden tener datos privados (p. ej. la clave de HERE).
     */
    public static String export(Context c, String player) {
        File out = new File(Environment.getExternalStorageDirectory(), "StradaDiag");
        out.mkdirs();
        if (!out.isDirectory() || !out.canWrite()) return null;   // memoria de solo lectura o sin permiso: Main avisa
        PackageManager pm = c.getPackageManager();
        int copied = 0;
        long bytes = 0;
        PrintWriter w = null;
        try {
            w = new PrintWriter(new FileOutputStream(new File(out, "info.txt")));
            w.println("Build: " + Build.MANUFACTURER + " / " + Build.MODEL + " / " + Build.DEVICE + " / " + Build.PRODUCT);
            w.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") " + Build.DISPLAY);
            w.println("Fingerprint: " + Build.FINGERPRINT);
            w.println();
            List<ApplicationInfo> list = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : list) {
                String label = String.valueOf(pm.getApplicationLabel(ai));
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                w.println((sys ? "[sys] " : "[usr] ") + ai.packageName + " | " + label + " | " + ai.sourceDir);
                String key = (ai.packageName + " " + label).toLowerCase(Locale.US);
                boolean want = false;
                for (String k : KEYS) if (key.contains(k)) { want = true; break; }
                if (ai.packageName.startsWith("com.google.") || ai.packageName.equals(c.getPackageName())) want = false;
                if (!want) continue;
                try {
                    PackageInfo pi = pm.getPackageInfo(ai.packageName,
                            PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS);
                    if (pi.services != null) for (ServiceInfo si : pi.services) w.println("      service  " + si.name + (si.exported ? " (exported)" : ""));
                    if (pi.receivers != null) for (ActivityInfo ri : pi.receivers) w.println("      receiver " + ri.name + (ri.exported ? " (exported)" : ""));
                    if (pi.providers != null) for (android.content.pm.ProviderInfo pr : pi.providers) w.println("      provider " + pr.name + " auth=" + pr.authority);
                } catch (Throwable ignored) { }
                File src = new File(ai.sourceDir);
                if (src.length() > 40L * 1024 * 1024) continue;
                bytes += copy(src, new File(out, ai.packageName + ".apk"));
                String base = ai.sourceDir.substring(0, ai.sourceDir.length() - 4);
                File odex = new File(base + ".odex");
                if (odex.exists()) bytes += copy(odex, new File(out, ai.packageName + ".odex"));
                copied++;
            }
        } catch (Throwable e) {
            if (w != null) w.println("ERROR: " + e);
        } finally {
            if (w != null) w.close();
        }
        // Bluetooth: servicios corriendo y eventos recientes (sirve para dar soporte a otras marcas)
        PrintWriter bw = null;
        try {
            bw = new PrintWriter(new FileOutputStream(new File(out, "bluetooth.txt")));
            bw.println("App Bluetooth Clinkway: " + (BtBridge.available(c) ? "sí" : "no") + " · activaciones: " + BtBridge.activations);
            bw.println();
            bw.println("Servicios corriendo ahora (haz el diagnóstico con el celular sonando por Bluetooth):");
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningServiceInfo> rs = am.getRunningServices(300);
            if (rs != null) for (android.app.ActivityManager.RunningServiceInfo r : rs) {
                bw.println("  " + r.service.flattenToShortString() + (r.foreground ? " [primer plano]" : "") + (r.started ? " [iniciado]" : "") + " clientes=" + r.clientCount);
            }
            bw.println();
            bw.println("Eventos Bluetooth de StradaOS:");
            bw.println(BtBridge.logText());
        } catch (Throwable e) {
            if (bw != null) bw.println("ERROR: " + e);
        } finally {
            if (bw != null) bw.close();
        }
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
        String ver = "";
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        // Reproductor: fuente, radio, Android Auto y los últimos eventos (lo mismo que al dejar presionado el reproductor)
        writeText(new File(out, "player.txt"), "StradaOS " + ver + " · " + now + "\n\n" + (player != null ? player : "(sin datos)"));
        // Registro de fallos: cierres inesperados y bloqueos (lo mismo que Ajustes → Acerca de → Registro de fallos)
        String crashes = Guard.read(c);
        writeText(new File(out, "crashes.txt"), "StradaOS " + ver + " · " + now + "\n\n" + (crashes != null ? crashes : "Sin fallos registrados."));
        return out.getAbsolutePath() + "  (" + copied + " apps, " + (bytes / 1024 / 1024) + " MB)";
    }

    private static void writeText(File f, String text) {
        PrintWriter w = null;
        try {
            w = new PrintWriter(new java.io.OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
            w.print(text);
        } catch (Throwable ignored) {
        } finally {
            if (w != null) w.close();
        }
    }

    private static long copy(File a, File b) {
        InputStream in = null;
        OutputStream os = null;
        long n = 0;
        try {
            in = new FileInputStream(a);
            os = new FileOutputStream(b);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) { os.write(buf, 0, r); n += r; }
        } catch (Throwable ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (os != null) os.close(); } catch (Throwable ignored) { }
        }
        return n;
    }
}
