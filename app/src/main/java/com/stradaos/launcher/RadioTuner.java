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
 * com.android.xygala.ACTION_CONTROL_RADIO y el extra «method»: method_open (enciende y pasa el sonido a la radio),
 * method_close, method_seekUp / method_seekDown (busca la siguiente emisora con señal), method_setFreq (param_freq),
 * method_band (method_setband). Avisa el estado con la difusión com.acloud.android.intent.RADIO, extra «method_value»
 * (texto que arma su biblioteca nativa; su formato se aprende del registro de eventos del diagnóstico).
 *
 * Unidades (las mismas que guarda la app de fábrica): FM en décimas de MHz (875 = 87,5 MHz), AM en kHz (522).
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

    /** Valor para el sintonizador: FM en décimas de MHz, AM en kHz. */
    static int unit(int band, int khz) { return band == FM ? Math.round(khz / 100f) : khz; }

    static String label(int band, int khz) {
        if (khz <= 0) return band == FM ? "FM" : "AM";
        if (band == FM) return String.format(java.util.Locale.US, "FM %.1f MHz", khz / 1000f);
        return "AM " + khz + " kHz";
    }

    // ── órdenes ──

    static boolean open(Context c) { return send(c, "method_open", null, 0); }

    static boolean close(Context c) { return send(c, "method_close", null, 0); }

    static boolean seek(Context c, SharedPreferences p, boolean up) {
        int band = band(p);
        return send(c, up ? "method_seekUp" : "method_seekDown", "param_freq", unit(band, khz(p)));
    }

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

    /** Enciende la radio y vuelve a la última emisora. */
    static boolean turnOn(Context c, SharedPreferences p) {
        if (!open(c)) return false;
        on = true;
        int k = khz(p);
        if (k > 0) tune(c, p, band(p), k);
        return true;
    }

    static void turnOff(Context c) {
        close(c);
        on = false;
        seeking = false;
    }

    static void ensureOn(Context c) { if (!on && open(c)) on = true; }

    static void step(Context c, SharedPreferences p, boolean up) {
        ensureOn(c);
        seek(c, p, up);
        seeking = true;
    }

    static void tuneTo(Context c, SharedPreferences p, int band, int khz) {
        ensureOn(c);
        seeking = false;
        tune(c, p, band, khz);
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
        int[] f = parse(v, band(p));
        if (f == null) return false;
        seeking = false;
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

    /**
     * Busca una frecuencia en el texto de estado. Devuelve {banda, kHz} o null. Acepta 101.1 / 1011 / 10110 / 101100
     * para FM y 522-1710 para AM; si cabe en las dos, gana la banda actual.
     */
    static int[] parse(String s, int currentBand) {
        if (s == null) return null;
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
