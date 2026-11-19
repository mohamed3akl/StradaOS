package com.stradaos.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idiomas de StradaOS: inglés y árabe. El código se escribe en español y cada texto se traduce al
 * mostrarse (tv(), toast(), ventanas TB, etc.) buscándolo en assets/i18n/LANG.txt.
 *
 * Formato del archivo: una línea por texto, «español<TAB>traducción». «\n» representa un salto de línea.
 * Un texto con {} es un patrón: «Guardado: {}<TAB>Saved: {}». Lo que va en {} también se traduce si está en la
 * tabla. En la traducción, {1}, {2}… permiten cambiar el orden. Si un texto no está en la tabla queda en español.
 */
public class I18n {
    static final String[] CODES = {"en", "ar"};
    static final String[] NAMES = {"English", "العربية"};

    static String lang = "en";
    static Locale loc = Locale.US;
    /** Locale de los números: en árabe se usan dígitos latinos (0-9), no ٠-٩. */
    static Locale num = loc;
    private static final HashMap<String, String> map = new HashMap<String, String>();
    private static final List<Pat> pats = new ArrayList<Pat>();
    private static final HashMap<String, String> cache = new HashMap<String, String>();

    private static class Pat {
        Pattern re;
        String out;
        int lit;   // letras fijas: se prueban primero los patrones más específicos
    }

    /** Idioma guardado («auto», «en», «ar»). Un idioma que ya no existe (español, portugués…) pasa a automático. */
    static String pref(SharedPreferences p) {
        String v = p.getString("lang", null);
        if (v == null || (!"auto".equals(v) && !java.util.Arrays.asList(CODES).contains(v))) {
            v = "auto";
            p.edit().putString("lang", v).apply();
        }
        return v;
    }

    /** Idioma del sistema si es uno de los disponibles; si no, inglés. */
    static String system() {
        String l = Locale.getDefault().getLanguage();
        for (String c : CODES) if (c.equals(l)) return l;
        return "en";
    }

    static void init(Context c, SharedPreferences p) {
        String v = pref(p);
        set(c, "auto".equals(v) ? system() : v);
    }

    static synchronized void set(Context c, String code) {
        if (!"ar".equals(code)) code = "en";          // solo inglés y árabe
        lang = code;
        loc = "ar".equals(code) ? new Locale("ar") : Locale.US;
        num = Locale.US;                              // dígitos latinos también en árabe
        applyConfig(c);
        map.clear();
        pats.clear();
        cache.clear();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("i18n/" + code + ".txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0 || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String k = line.substring(0, tab).replace("\\n", "\n");
                String t = line.substring(tab + 1).replace("\\n", "\n");
                if (k.contains("{}")) addPattern(k, t);
                else map.put(k, t);
            }
            r.close();
        } catch (Throwable e) {
            Guard.log("No se pudo cargar el idioma " + code + ": " + e);
        }
        java.util.Collections.sort(pats, new java.util.Comparator<Pat>() {
            public int compare(Pat a, Pat b) { return b.lit - a.lit; }
        });
    }

    private static void addPattern(String k, String t) {
        StringBuilder re = new StringBuilder("^");
        String[] parts = k.split("\\{\\}", -1);
        int lit = 0;
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) re.append("(.+?)");
            re.append(Pattern.quote(parts[i]));
            lit += parts[i].length();
        }
        re.append("$");
        Pat p = new Pat();
        p.re = Pattern.compile(re.toString(), Pattern.DOTALL);
        p.out = t;
        p.lit = lit;
        pats.add(p);
    }

    static String t(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString();
        if ("es".equals(lang) || s.length() == 0) return s;
        return tr(s, 0);
    }

    /** Texto de varias líneas (diagnósticos): se traduce cada línea por separado. */
    static String lines(String s) {
        if (s == null || "es".equals(lang)) return s;
        String[] ls = s.split("\n", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) b.append('\n');
            b.append(ls[i].length() == 0 ? "" : t(ls[i]));
        }
        return b.toString();
    }

    /** Texto con %s, %d… (formato de Java) traducido y luego completado. */
    static String f(String es, Object... args) {
        return String.format(num, t(es), args);
    }

    private static synchronized String tr(String s, int depth) {
        String hit = map.get(s);
        if (hit != null) return hit;
        if (depth > 3 || s.length() > 600) return s;
        String c = cache.get(s);
        if (c != null) return c;
        String out = s;
        // espacios alrededor (p. ej. «  Cerrar ✕»): se traducen sin ellos
        String trim = s.trim();
        if (trim.length() != s.length() && trim.length() > 0) {
            String in = tr(trim, depth + 1);
            if (!in.equals(trim)) out = s.replace(trim, in);
        }
        if (out == s) {
            for (Pat p : pats) {
                Matcher m = p.re.matcher(s);
                if (!m.matches()) continue;
                String r = p.out;
                int seq = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.length(); i++) {
                    char ch = r.charAt(i);
                    if (ch == '{' && i + 1 < r.length()) {
                        int close = r.indexOf('}', i);
                        if (close > i) {
                            String inner = r.substring(i + 1, close);
                            int g = -1;
                            if (inner.length() == 0) g = ++seq;
                            else try { g = Integer.parseInt(inner); } catch (NumberFormatException ignored) { }
                            if (g >= 1 && g <= m.groupCount()) {
                                sb.append(tr(m.group(g), depth + 1));
                                i = close;
                                continue;
                            }
                        }
                    }
                    sb.append(ch);
                }
                out = sb.toString();
                break;
            }
        }
        if (cache.size() > 3000) cache.clear();
        cache.put(s, out);
        return out;
    }

    /** Dirección de todas las ventanas de la app (pantallas, ventanas emergentes): de derecha a izquierda en árabe. */
    static void applyConfig(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            try {
                android.content.res.Resources res = c.getResources();
                android.content.res.Configuration cf = res.getConfiguration();
                cf.setLayoutDirection(loc);
                res.updateConfiguration(cf, res.getDisplayMetrics());
                if (c.getApplicationContext() != null && c.getApplicationContext().getResources() != res) {
                    android.content.res.Resources ar = c.getApplicationContext().getResources();
                    android.content.res.Configuration ac = ar.getConfiguration();
                    ac.setLayoutDirection(loc);
                    ar.updateConfiguration(ac, ar.getDisplayMetrics());
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Árabe se muestra de derecha a izquierda (Android 4.2+, API 17: antes no hay soporte de RTL). */
    static boolean rtl() {
        return "ar".equals(lang) && android.os.Build.VERSION.SDK_INT >= 17;
    }

    /** Pone la dirección de la interfaz (derecha a izquierda en árabe, izquierda a derecha en inglés) en v y sus hijos. */
    static void applyDirection(android.view.View v) {
        if (v == null || android.os.Build.VERSION.SDK_INT < 17) return;
        v.setLayoutDirection(rtl() ? android.view.View.LAYOUT_DIRECTION_RTL : android.view.View.LAYOUT_DIRECTION_LTR);
    }

    /** Gravedad izquierda/derecha según la dirección: en árabe «izquierda» es la derecha y viceversa. */
    static int left() { return rtl() ? android.view.Gravity.RIGHT : android.view.Gravity.LEFT; }
    static int right() { return rtl() ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT; }

    /** Margen al inicio de la línea (izquierda en inglés, derecha en árabe). */
    static void marginStart(android.view.ViewGroup.MarginLayoutParams lp, int px) {
        if (rtl()) lp.rightMargin = px; else lp.leftMargin = px;
    }

    static void marginEnd(android.view.ViewGroup.MarginLayoutParams lp, int px) {
        if (rtl()) lp.leftMargin = px; else lp.rightMargin = px;
    }

    /** Números enteros con el locale de los números (dígitos latinos en árabe). */
    static String integer(long v) {
        return java.text.NumberFormat.getIntegerInstance(num).format(v);
    }

    /** Fecha/hora con nombres del idioma pero dígitos latinos (en árabe, Android usaría ٠-٩). */
    static java.text.DateFormat latin(java.text.DateFormat df) {
        if (num != loc) {
            java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance(num);
            nf.setGroupingUsed(false);
            df.setNumberFormat(nf);
        }
        return df;
    }

    static java.text.SimpleDateFormat sdf(String pattern) {
        return (java.text.SimpleDateFormat) latin(new java.text.SimpleDateFormat(pattern, loc));
    }

    /** Patrón de la fecha de la pantalla de inicio. */
    static String datePattern() {
        if ("ar".equals(lang)) return "EEEE، d MMMM";
        return "EEEE, MMMM d";
    }
}
