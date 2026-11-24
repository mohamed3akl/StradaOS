package com.stradaos.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La radio manejada por StradaOS, sin abrir la app de la radio (pantallas XY / PVE «acloud», chip AC8317).
 *
 * La app de fábrica com.acloud.stub.localradio tiene un servicio en segundo plano (com.radio.service.RadioService,
 * exportado y sin permiso) que maneja el sintonizador. Recibe órdenes con startService, acción
 * com.android.xygala.ACTION_CONTROL_RADIO y el extra «method»: method_open (enciende el chip; el sonido se pasa aparte, ver audioToRadio),
 * method_close, method_seekUp / method_seekDown (busca la siguiente emisora con señal), method_setFreq (param_freq),
 * method_band (method_setband). Avisa el estado con la difusión com.acloud.android.intent.RADIO, extra «method_value»
 * (texto que arma su biblioteca nativa; su formato se aprende del registro de eventos del diagnóstico).
 *
 * Unidades: el servicio recibe y avisa la frecuencia en kHz en las dos bandas (FM 101,1 MHz = 101100, AM 522). La app de
 * fábrica guarda FM en décimas de MHz (1011) pero multiplica por 100 antes de llamar al servicio; mandar 1011 en FM
 * el sintonizador lo toma como 1011 kHz (zona de AM) y la búsqueda recorre la banda sin encontrar nada.
 * StradaOS guarda la frecuencia en kHz para las dos bandas (FM 101,1 MHz = 101100).
 */
class RadioTuner {

    static final String PKG = "com.acloud.stub.localradio";
    static final String SERVICE = "com.radio.service.RadioService";
    static final String ACTION = "com.android.xygala.ACTION_CONTROL_RADIO";
    static final String STATUS = "com.acloud.android.intent.RADIO";

    static final int FM = 0, AM = 1;
    static final int FM_MIN = 87500, FM_MAX = 108000, AM_MIN = 522, AM_MAX = 1710;

    private static Boolean available;

    /** true si esta pantalla tiene el servicio del sintonizador (la radio puede sonar sin abrir su app). */
    static boolean available(Context c) {
        if (available == null) {
            boolean ok = false;
            try {
                ServiceInfo si = c.getPackageManager().getServiceInfo(new ComponentName(PKG, SERVICE), 0);
                ok = si != null && si.exported;
            } catch (Throwable ignored) { }
            available = ok;
        }
        return available;
    }

    private static boolean send(Context c, String method, String key, int value) {
        Intent i = new Intent(ACTION);
        i.setComponent(new ComponentName(PKG, SERVICE));
        i.putExtra("method", method);
        if (key != null) i.putExtra(key, value);
        try {
            ComponentName cn = c.startService(i);
            BtBridge.log("radio directa: " + method + (key != null ? " " + key + "=" + value : "") + (cn == null ? " (sin servicio)" : ""));
            return cn != null;
        } catch (Throwable e) {
            BtBridge.log("radio directa: " + method + " falló: " + e);
            return false;
        }
    }

    // ── estado guardado ──

    static int band(SharedPreferences p) { return p.getInt("tuner_band", FM); }

    /** Frecuencia en kHz, o 0 si aún no se conoce. */
    static int khz(SharedPreferences p) { return p.getInt("tuner_khz_" + band(p), 0); }

    static void remember(SharedPreferences p, int band, int khz) {
        p.edit().putInt("tuner_band", band).putInt("tuner_khz_" + band, khz).apply();
    }

    /** Valor para el sintonizador: kHz en las dos bandas (como lo manda la app de fábrica). */
    static int unit(int band, int khz) { return khz; }

    static String label(int band, int khz) {
        if (khz <= 0) return band == FM ? "FM" : "AM";
        if (band == FM) return String.format(java.util.Locale.US, "FM %.1f MHz", khz / 1000f);
        return "AM " + khz + " kHz";
    }

    // ── órdenes ──

    static boolean open(Context c) {
        boolean ok = send(c, "method_open", null, 0);
        if (ok) audioToRadio(c);
        return ok;
    }

    static boolean close(Context c) {
        audioToGps(c);
        return send(c, "method_close", null, 0);
    }

    /**
     * El sonido del sintonizador entra por la entrada analógica (AVIN) del chip: la abre el servicio de fábrica
     * com.autochips.backcarapp (BackCarService) cuando recibe esta difusión, un segundo después. Es lo que hace la app
     * de la radio al reproducir (RadioService.interfaceSetAudioToRadio); method_open solo prepara el chip de radio.
     */
    static void audioToRadio(Context c) {
        try {
            c.sendBroadcast(new Intent("xy.android.galaxradio.setaudiotoradio")
                .putExtra("fm", "setaudiotoradio").putExtra("isToGps", false));
            BtBridge.log("radio directa: sonido a la radio (xy.android.galaxradio.setaudiotoradio)");
        } catch (Throwable e) { BtBridge.log("radio directa: sonido a la radio falló: " + e); }
    }

    /** Cierra la entrada de sonido de la radio (lo que hace la app de la radio al pausar o detener). */
    static void audioToGps(Context c) {
        try {
            c.sendBroadcast(new Intent("xy.android.galaxradio.setaudiotogps").putExtra("fm", "setaudiotogps"));
            BtBridge.log("radio directa: sonido fuera de la radio (xy.android.galaxradio.setaudiotogps)");
        } catch (Throwable e) { BtBridge.log("radio directa: cerrar sonido de la radio falló: " + e); }
    }

    /** Busca desde la emisora actual, como la app de fábrica (nunca desde 0: el sintonizador se queda buscando). */
    static boolean seek(Context c, SharedPreferences p, boolean up) {
        int band = band(p), k = khz(p);
        if (k <= 0) { k = bandMin(band); remember(p, band, k); }
        return send(c, up ? "method_seekUp" : "method_seekDown", "param_freq", unit(band, k));
    }

    static int bandMin(int band) { return band == FM ? FM_MIN : AM_MIN; }

    static boolean tune(Context c, SharedPreferences p, int band, int khz) {
        if (band != band(p)) setBand(c, band);
        remember(p, band, khz);
        return send(c, "method_setFreq", "param_freq", unit(band, khz));
    }

    /** Banda del sintonizador: en la app de fábrica 0-2 son FM1-FM3 y 3-4 AM1-AM2 (se usa FM1 y AM1). */
    static void setBand(Context c, int band) {
        Intent i = new Intent(ACTION);
        i.setComponent(new ComponentName(PKG, SERVICE));
        i.putExtra("method", "method_band");
        i.putExtra("method_setband", band == FM ? 0 : 3);
        try { c.startService(i); BtBridge.log("radio directa: method_band " + (band == FM ? "FM (0)" : "AM (3)")); }
        catch (Throwable e) { BtBridge.log("radio directa: method_band falló: " + e); }
    }

    // ── acciones compartidas por el reproductor del inicio y la pantalla de la radio ──

    /** Lo que StradaOS cree: la radio está encendida (se pidió ▶ y no ■ después). */
    static volatile boolean on;
    /** Se pidió buscar emisora: la frecuencia nueva llega con el aviso del sintonizador. */
    static volatile boolean seeking;
    /** Último aviso del sintonizador, tal cual (para el diagnóstico). */
    static volatile String lastStatus;

    /** Ya llegó algún aviso del sintonizador desde que arrancó StradaOS. */
    static volatile boolean statusSeen;
    private static boolean restarted;

    /**
     * El servicio de la radio avisa el estado a un solo «oyente»: el suyo (que manda la difusión que lee StradaOS) hasta
     * que se abre la app de la radio, que lo reemplaza y no lo devuelve al cerrarse. Si aún no llegó ningún aviso, se
     * reinicia el servicio una vez (solo con la radio apagada) para que vuelva a avisar a StradaOS.
     */
    private static void restartServiceOnce(Context c) {
        if (restarted || statusSeen) return;
        restarted = true;
        try {
            ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(PKG);
            BtBridge.log("radio directa: servicio de la radio reiniciado para recibir sus avisos");
        } catch (Throwable e) { BtBridge.log("radio directa: no se pudo reiniciar el servicio: " + e); }
    }

    /** Enciende la radio en la última emisora (o al inicio de la banda): banda y frecuencia siempre se mandan. */
    static boolean turnOn(Context c, SharedPreferences p) {
        if (!on) restartServiceOnce(c);
        if (!open(c)) return false;
        on = true;
        int band = band(p), k = khz(p);
        if (k <= 0) k = bandMin(band);
        setBand(c, band);
        tune(c, p, band, k);
        return true;
    }

    static void turnOff(Context c) {
        stopWaiting();
        close(c);
        on = false;
        seeking = false;
    }

    static void ensureOn(Context c, SharedPreferences p) { if (!on) turnOn(c, p); }

    static void step(Context c, SharedPreferences p, boolean up) {
        ensureOn(c, p);
        seek(c, p, up);
        seeking = true;
        waitForStation(c, p, up);
    }

    static void tuneTo(Context c, SharedPreferences p, int band, int khz) {
        ensureOn(c, p);
        stopWaiting();
        seeking = false;
        tune(c, p, band, khz);
    }

    // ── si el sintonizador no contesta, no se queda «Buscando emisora…» para siempre ──

    static final long SEEK_TIMEOUT = 10000;
    private static final android.os.Handler H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable timeout;

    private static void stopWaiting() { if (timeout != null) { H.removeCallbacks(timeout); timeout = null; } }

    private static void waitForStation(final Context c, final SharedPreferences p, final boolean up) {
        stopWaiting();
        final Context app = c.getApplicationContext();
        timeout = new Runnable() { public void run() {
            timeout = null;
            if (!seeking) return;
            seeking = false;
            send(app, "method_seekStop", null, 0);
            BtBridge.log("radio directa: el sintonizador no avisó en " + SEEK_TIMEOUT / 1000 + " s");
            // sin avisos: se pasa a la emisora guardada siguiente / anterior
            int band = band(p), k = khz(p);
            List<Integer> l = favorites(p, band);
            Integer go = null;
            for (int i = 0; i < l.size(); i++) {
                int f = l.get(up ? i : l.size() - 1 - i);
                if (up ? f > k : f < k) { go = f; break; }
            }
            if (go == null && !l.isEmpty()) go = l.get(up ? 0 : l.size() - 1);
            if (go != null) tune(app, p, band, go);
            try {
                android.widget.Toast.makeText(app, I18n.t(go != null ? "La radio no contestó: emisora guardada"
                    : "La radio no contestó a la búsqueda"), android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
            // avisa a las pantallas de StradaOS para que se redibujen
            try { app.sendBroadcast(new Intent(STATUS).setPackage(app.getPackageName())); } catch (Throwable ignored) { }
        }};
        H.postDelayed(timeout, SEEK_TIMEOUT);
    }

    /** FM ↔ AM: va a la última emisora de la otra banda (o al inicio de la banda). */
    static void switchBand(Context c, SharedPreferences p) {
        int nb = band(p) == FM ? AM : FM;
        int nk = p.getInt("tuner_khz_" + nb, 0);
        if (nk <= 0) nk = nb == FM ? FM_MIN : AM_MIN;
        tuneTo(c, p, nb, nk);
    }

    /** Guarda la emisora actual. false si aún no se conoce la frecuencia. */
    static boolean saveCurrent(SharedPreferences p) {
        int band = band(p), k = khz(p);
        if (k <= 0) return false;
        List<Integer> l = favorites(p, band);
        if (!l.contains(k)) { l.add(k); saveFavorites(p, band, l); }
        return true;
    }

    static void remove(SharedPreferences p, int band, int k) {
        List<Integer> l = favorites(p, band);
        l.remove(Integer.valueOf(k));
        saveFavorites(p, band, l);
    }

    /** Aviso del sintonizador: se anota y, si trae una frecuencia, se guarda. true si cambió la emisora mostrada. */
    static boolean onStatus(SharedPreferences p, String v) {
        if (v == null) return false;
        if (!v.equals(lastStatus)) BtBridge.log("radio directa ← " + (v.length() > 160 ? v.substring(0, 160) + "…" : v));
        lastStatus = v;
        statusSeen = true;
        int[] f = parse(v, band(p));
        if (f == null) return false;
        // JSON del sintonizador: {"Radio":{"Action":"TuneOK"|"ScanOK"|…,"Freq":1011}}; mientras busca puede ir
        // avisando frecuencias; la búsqueda termina con una acción «…OK» (o con un aviso que no es JSON)
        Matcher a = ACT.matcher(v);
        boolean done = !a.find() || a.group(1).toUpperCase(java.util.Locale.US).endsWith("OK");
        if (done) { seeking = false; stopWaiting(); }
        if (f[0] == band(p) && f[1] == khz(p)) return false;
        remember(p, f[0], f[1]);
        return true;
    }

    /** Texto corto para la cuadrícula: «101.1» o «522». */
    static String shortLabel(int band, int khz) {
        return band == FM ? String.format(java.util.Locale.US, "%.1f", khz / 1000f) : String.valueOf(khz);
    }

    // ── favoritas ──

    static List<Integer> favorites(SharedPreferences p, int band) {
        List<Integer> out = new ArrayList<Integer>();
        String s = p.getString("tuner_fav_" + band, "");
        for (String x : s.split(",")) {
            try { if (x.length() > 0) out.add(Integer.parseInt(x.trim())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void saveFavorites(SharedPreferences p, int band, List<Integer> l) {
        java.util.Collections.sort(l);
        StringBuilder b = new StringBuilder();
        for (Integer k : l) { if (b.length() > 0) b.append(','); b.append(k); }
        p.edit().putString("tuner_fav_" + band, b.toString()).apply();
    }

    // ── estado que avisa el sintonizador ──

    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern FREQ = Pattern.compile("\"Freq\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)");
    private static final Pattern ACT = Pattern.compile("\"Action\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Busca una frecuencia en el texto de estado. Devuelve {banda, kHz} o null. Acepta 101.1 / 1011 / 10110 / 101100
     * para FM y 522-1710 para AM; si cabe en las dos, gana la banda actual.
     */
    static int[] parse(String s, int currentBand) {
        if (s == null) return null;
        Matcher j = FREQ.matcher(s);
        if (j.find()) s = j.group(1);   // aviso JSON: solo cuenta el campo Freq
        Matcher m = NUM.matcher(s);
        int[] amHit = null;
        while (m.find()) {
            String t = m.group();
            double v;
            try { v = Double.parseDouble(t); } catch (NumberFormatException e) { continue; }
            int fm = 0;
            if (t.contains(".") && v >= 87.5 && v <= 108) fm = (int) Math.round(v * 1000);
            else if (!t.contains(".")) {
                if (v >= 875 && v <= 1080) fm = (int) v * 100;
                else if (v >= 8750 && v <= 10800) fm = (int) v * 10;
                else if (v >= 87500 && v <= 108000) fm = (int) v;
            }
            boolean am = !t.contains(".") && v >= AM_MIN && v <= AM_MAX;
            if (fm > 0 && !(am && currentBand == AM)) return new int[]{FM, fm};
            if (am && amHit == null) amHit = new int[]{AM, (int) v};
        }
        return amHit;
    }
}
