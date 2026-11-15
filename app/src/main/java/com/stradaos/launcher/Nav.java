package com.stradaos.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Navegación paso a paso: busca destinos (Nominatim), calcula la ruta en carro (OSRM) por internet y guía con la
 * posición GPS: siguiente giro, distancia, tiempo y hora de llegada. Recalcula si te sales de la ruta.
 * El mapa sigue siendo el de StradaOS (sin conexión o en vivo); solo la búsqueda y el cálculo de ruta usan internet.
 * Datos © colaboradores de OpenStreetMap (ODbL). Una sola instancia; todo en el hilo principal.
 */
public class Nav {

    public interface Listener {
        /** Cambió el estado, la ruta o el avance. */
        void onNav();
    }

    public interface SearchCb { void onResult(List<Place> places, String error); }

    public static class Place {
        public String name, detail;
        public double lat, lon;
        JSONObject json() throws Exception {
            return new JSONObject().put("n", name).put("d", detail == null ? "" : detail).put("la", lat).put("lo", lon);
        }
        static Place from(JSONObject o) {
            Place p = new Place();
            p.name = o.optString("n");
            p.detail = o.optString("d");
            p.lat = o.optDouble("la");
            p.lon = o.optDouble("lo");
            return p;
        }
    }

    /** Un giro de la ruta. */
    public static class Step {
        public String type, modifier, name;
        public int exit;
        public double lat, lon;
        public double at;          // metros desde el inicio de la ruta hasta este giro
        boolean warnedFar, warnedNear;
    }

    static final int IDLE = 0, ROUTING = 1, GUIDING = 2, ARRIVED = 3, ERROR = 4;

    static final String NOMINATIM = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&addressdetails=0";
    /** Servidores OSRM públicos (mismo formato): si uno falla se prueba el siguiente. */
    // En Android 4.x algunos servidores rechazan la conexión segura (SSLHandshakeException): se prueba también
    // por http. La ruta solo lleva el punto de partida y el destino.
    static final String[] OSRMS = {"https://routing.openstreetmap.de/routed-car/route/v1/driving/",
            "https://router.project-osrm.org/route/v1/driving/",
            "http://router.project-osrm.org/route/v1/driving/",
            "http://routing.openstreetmap.de/routed-car/route/v1/driving/"};
    static final String PHOTON = "https://photon.komoot.io/api/?limit=8";

    /** Motivo corto del último fallo de red (para mostrarlo y saber qué pasó). */
    static String netWhy(Throwable e) {
        if (e == null) return "";
        String m = e.getMessage();
        String n = e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) return "no internet (DNS lookup failed)";
        if (e instanceof java.net.SocketTimeoutException) return "server did not answer in time";
        if (e instanceof javax.net.ssl.SSLException) return "secure connection refused (" + n + ")";
        return n + (m != null ? ": " + (m.length() > 80 ? m.substring(0, 80) : m) : "");
    }

    private static Nav inst;

    static synchronized Nav get(Context c) {
        if (inst == null) inst = new Nav(c.getApplicationContext());
        return inst;
    }

    /** Ruta activa para dibujarla en el mapa (coordenadas Mercator normalizadas 0–1). null si no hay. */
    static volatile double[] routeMx, routeMy;
    /** Índice del primer punto que falta por recorrer (para dibujar solo lo que queda). */
    static volatile int routeFrom;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final LocationManager lm;

    int state = IDLE;
    String error;
    Place dest;
    private double[] lat, lon, cum;         // puntos de la ruta y distancia acumulada
    private final List<Step> steps = new ArrayList<Step>();
    private double totalM, totalS;
    private int matchIdx;                    // segmento donde va el carro
    private double along;                    // metros recorridos sobre la ruta
    private int stepIdx;                     // próximo giro
    private int offCount;
    private long lastReroute;
    Location last;
    private boolean listening;

    private Nav(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("nav", Context.MODE_PRIVATE);
        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void fire() { for (Listener l : new ArrayList<Listener>(listeners)) l.onNav(); }

    boolean active() { return state == ROUTING || state == GUIDING; }

    // ───────────────────────── búsqueda ─────────────────────────

    void search(final String q, final SearchCb cb) {
        final Location here = bestLocation();
        new Thread(new Runnable() { public void run() {
            List<Place> out = new ArrayList<Place>();
            String err = null;
            Throwable why = null;
            try {
                out = nominatim(q, here);
            } catch (Throwable e) {
                why = e;
                try { out = photon(q, here); why = null; } catch (Throwable e2) { if (!(e instanceof java.net.UnknownHostException)) why = e2; }
            }
            if (why != null) err = "Sin internet: no se pudo buscar. Activa el punto de acceso del celular.\n" + netWhy(why);
            else if (here != null) sortByDistance(out, here);
            final List<Place> res = out;
            final String e2 = err;
            h.post(new Runnable() { public void run() { cb.onResult(res, e2); } });
        }}).start();
    }

    private static List<Place> nominatim(String q, Location here) throws Exception {
        List<Place> out = new ArrayList<Place>();
        String lang = Locale.getDefault().getLanguage();
        StringBuilder u = new StringBuilder(NOMINATIM);
        u.append("&q=").append(URLEncoder.encode(q, "UTF-8"));
        u.append("&accept-language=").append(URLEncoder.encode(I18n.lang + "," + lang + ",en", "UTF-8"));
        if (here != null) {
            // preferir resultados cerca (sin excluir los lejanos)
            double la = here.getLatitude(), lo = here.getLongitude();
            u.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", lo - 0.6, la + 0.6, lo + 0.6, la - 0.6));
        }
        JSONArray a = new JSONArray(new String(Net.get(u.toString(), 15000), "UTF-8"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Place p = new Place();
            String full = o.optString("display_name", "");
            String nm = o.optString("name", "");
            int comma = full.indexOf(',');
            p.name = nm.length() > 0 ? nm : comma > 0 ? full.substring(0, comma) : full;
            p.detail = comma > 0 ? full.substring(comma + 1).trim() : "";
            p.lat = Double.parseDouble(o.getString("lat"));
            p.lon = Double.parseDouble(o.getString("lon"));
            out.add(p);
        }
        return out;
    }

    /** Buscador de respaldo (Photon, también con datos de OpenStreetMap). */
    private static List<Place> photon(String q, Location here) throws Exception {
        List<Place> out = new ArrayList<Place>();
        StringBuilder u = new StringBuilder(PHOTON);
        u.append("&q=").append(URLEncoder.encode(q, "UTF-8"));
        if (here != null) u.append(String.format(Locale.US, "&lat=%.5f&lon=%.5f", here.getLatitude(), here.getLongitude()));
        JSONArray fs = new JSONObject(new String(Net.get(u.toString(), 15000), "UTF-8")).getJSONArray("features");
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.getJSONObject(i);
            JSONObject pr = f.getJSONObject("properties");
            JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
            Place p = new Place();
            p.name = pr.optString("name", pr.optString("street", "?"));
            StringBuilder d = new StringBuilder();
            for (String k : new String[]{"street", "district", "city", "state", "country"}) {
                String v = pr.optString(k, "");
                if (v.length() > 0 && !v.equals(p.name)) { if (d.length() > 0) d.append(", "); d.append(v); }
            }
            p.detail = d.toString();
            p.lon = c.getDouble(0);
            p.lat = c.getDouble(1);
            out.add(p);
        }
        return out;
    }

    private static void sortByDistance(List<Place> l, final Location here) {
        java.util.Collections.sort(l, new java.util.Comparator<Place>() {
            public int compare(Place a, Place b) {
                double da = dist(here.getLatitude(), here.getLongitude(), a.lat, a.lon);
                double db = dist(here.getLatitude(), here.getLongitude(), b.lat, b.lon);
                return da < db ? -1 : da > db ? 1 : 0;
            }
        });
    }

    List<Place> recents() {
        List<Place> out = new ArrayList<Place>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Place.from(a.getJSONObject(i)));
        } catch (Throwable ignored) { }
        return out;
    }

    private void remember(Place p) {
        try {
            List<Place> l = recents();
            JSONArray a = new JSONArray();
            a.put(p.json());
            for (Place x : l) {
                if (a.length() >= 8) break;
                if (dist(x.lat, x.lon, p.lat, p.lon) < 50) continue;
                a.put(x.json());
            }
            prefs.edit().putString("recent", a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── ruta ─────────────────────────

    /** Empieza a navegar hacia p desde la posición actual. */
    void start(Place p) {
        dest = p;
        remember(p);
        listen(true);
        state = ROUTING;
        error = null;
        fire();
        Location here = bestLocation();
        if (here == null) {
            // sin posición todavía: se calcula en cuanto llegue la primera lectura del GPS
            return;
        }
        route(here);
    }

    void stop() {
        state = IDLE;
        dest = null;
        error = null;
        routeMx = routeMy = null;
        steps.clear();
        listen(false);
        fire();
    }

    private boolean routing;

    private void route(final Location from) {
        if (routing || dest == null) return;
        routing = true;
        lastReroute = SystemClock.elapsedRealtime();
        final Place to = dest;
        new Thread(new Runnable() { public void run() {
            JSONObject r = null;
            Throwable why = null;
            for (String base : OSRMS) {
            try {
                String u = base + String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", from.getLongitude(), from.getLatitude(), to.lon, to.lat)
                        + "?overview=full&geometries=polyline&steps=true"
                        + (from.hasBearing() ? String.format(Locale.US, "&bearings=%d,60;", Math.round(from.getBearing()) % 360) : "");
                JSONObject o = new JSONObject(new String(Net.get(u, 20000), "UTF-8"));
                if ("Ok".equals(o.optString("code"))) { r = o.getJSONArray("routes").getJSONObject(0); break; }
                why = new Exception("OSRM: " + o.optString("code"));
            } catch (Throwable e) { why = e; }
            }
            final JSONObject route = r;
            final String reason = netWhy(why);
            h.post(new Runnable() { public void run() {
                routing = false;
                if (dest != to) return;                       // terminó o cambió de destino mientras tanto
                if (route == null) {
                    if (lat == null) { state = ERROR; error = I18n.t("Sin internet: no se pudo calcular la ruta") + "\n" + reason; }
                    fire();                                    // si ya había ruta se sigue con ella
                    return;
                }
                try { load(route); } catch (Throwable e) { state = ERROR; error = "No se pudo calcular la ruta"; fire(); return; }
                state = GUIDING;
                offCount = 0;
                if (last != null) progress(last);
                fire();
            }});
        }}).start();
    }

    private void load(JSONObject r) throws Exception {
        List<double[]> pts = decode(r.getString("geometry"));
        int n = pts.size();
        lat = new double[n]; lon = new double[n]; cum = new double[n];
        double[] mx = new double[n], my = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = pts.get(i)[0];
            lon[i] = pts.get(i)[1];
            cum[i] = i == 0 ? 0 : cum[i - 1] + dist(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            mx[i] = (lon[i] + 180.0) / 360.0;
            double lr = Math.toRadians(lat[i]);
            my[i] = (1.0 - Math.log(Math.tan(lr) + 1.0 / Math.cos(lr)) / Math.PI) / 2.0;
        }
        totalM = r.optDouble("distance", n > 0 ? cum[n - 1] : 0);
        totalS = r.optDouble("duration", 0);
        steps.clear();
        JSONArray legs = r.getJSONArray("legs");
        int searchFrom = 0;
        for (int li = 0; li < legs.length(); li++) {
            JSONArray ss = legs.getJSONObject(li).getJSONArray("steps");
            for (int i = 0; i < ss.length(); i++) {
                JSONObject s = ss.getJSONObject(i);
                JSONObject m = s.getJSONObject("maneuver");
                Step st = new Step();
                st.type = m.optString("type");
                st.modifier = m.optString("modifier", "");
                st.exit = m.optInt("exit", 0);
                JSONArray loc = m.getJSONArray("location");
                st.lon = loc.getDouble(0);
                st.lat = loc.getDouble(1);
                String nm = s.optString("name", "");
                String ref = s.optString("ref", "");
                st.name = nm.length() > 0 ? nm : ref;
                // posición del giro sobre la ruta: el punto más cercano a partir del giro anterior
                int best = searchFrom;
                double bd = Double.MAX_VALUE;
                for (int k = searchFrom; k < n; k++) {
                    double d = dist(lat[k], lon[k], st.lat, st.lon);
                    if (d < bd) { bd = d; best = k; }
                    if (bd < 2 && d > 200) break;
                }
                st.at = cum[best];
                searchFrom = best;
                if ("depart".equals(st.type) && i == 0 && li == 0) continue;   // «arranca» no se anuncia
                steps.add(st);
            }
        }
        matchIdx = 0;
        along = 0;
        stepIdx = 0;
        routeFrom = 0;
        routeMy = my;
        routeMx = mx;
    }

    /** Polilínea codificada de Google (precisión 5), la que usa OSRM. */
    static List<double[]> decode(String s) {
        List<double[]> out = new ArrayList<double[]>();
        int i = 0, la = 0, lo = 0;
        while (i < s.length()) {
            int[] r = new int[2];
            for (int k = 0; k < 2; k++) {
                int shift = 0, res = 0, b;
                do { b = s.charAt(i++) - 63; res |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20 && i < s.length());
                r[k] = (res & 1) != 0 ? ~(res >> 1) : (res >> 1);
            }
            la += r[0];
            lo += r[1];
            out.add(new double[]{la / 1e5, lo / 1e5});
        }
        return out;
    }

    // ───────────────────────── avance con el GPS ─────────────────────────

    private final LocationListener gps = new LocationListener() {
        public void onLocationChanged(Location l) { onLocation(l); }
        public void onStatusChanged(String p, int s, Bundle b) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
    };

    private void listen(boolean on) {
        if (on == listening) return;
        listening = on;
        try {
            if (on) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gps, Looper.getMainLooper());
                try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, gps, Looper.getMainLooper()); } catch (Throwable ignored) { }
            } else lm.removeUpdates(gps);
        } catch (Throwable ignored) { }
    }

    Location bestLocation() {
        if (last != null && System.currentTimeMillis() - last.getTime() < 120000) return last;
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) { }
        }
        return best;
    }

    void onLocation(Location l) {
        if (l == null) return;
        // una lectura de red no reemplaza a una de GPS reciente
        if (!LocationManager.GPS_PROVIDER.equals(l.getProvider()) && last != null
                && LocationManager.GPS_PROVIDER.equals(last.getProvider()) && l.getTime() - last.getTime() < 10000) return;
        last = l;
        if (state == ROUTING && lat == null && !routing) { route(l); }
        if (state == GUIDING) progress(l);
        fire();
    }

    private void progress(Location l) {
        if (lat == null || lat.length < 2) return;
        double la = l.getLatitude(), lo = l.getLongitude();
        // buscar el segmento más cercano un poco atrás y bastante adelante del último
        int from = Math.max(0, matchIdx - 5), to = Math.min(lat.length - 2, matchIdx + 300);
        int bi = matchIdx;
        double bd = Double.MAX_VALUE, bt = 0;
        for (int i = from; i <= to; i++) {
            double[] pr = project(la, lo, lat[i], lon[i], lat[i + 1], lon[i + 1]);
            if (pr[0] < bd) { bd = pr[0]; bi = i; bt = pr[1]; }
        }
        double acc = l.hasAccuracy() ? l.getAccuracy() : 20;
        if (bd > Math.max(50, acc * 1.5)) {
            if (++offCount >= 3 && SystemClock.elapsedRealtime() - lastReroute > 12000) {
                offCount = 0;
                route(l);                                     // recalcular desde aquí
            }
        } else offCount = 0;
        matchIdx = bi;
        along = cum[bi] + (cum[bi + 1] - cum[bi]) * bt;
        routeFrom = bi;
        while (stepIdx < steps.size() && steps.get(stepIdx).at < along - 15) stepIdx++;
        if (remaining() < 30 || stepIdx >= steps.size()) {
            state = ARRIVED;
            chime(3);
            listen(false);
            routeMx = routeMy = null;
            final Place arrivedAt = dest;
            // el aviso de llegada se queda un minuto y luego todo vuelve a la normalidad
            h.postDelayed(new Runnable() { public void run() { if (state == ARRIVED && dest == arrivedAt) stop(); } }, 60000);
            return;
        }
        Step s = steps.get(stepIdx);
        double d = s.at - along;
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 30;
        double far = kmh > 70 ? 800 : kmh > 40 ? 400 : 250;
        if (!s.warnedFar && d < far && d > 120) { s.warnedFar = true; chime(1); }
        if (!s.warnedNear && d < Math.max(40, kmh * 1.6)) { s.warnedNear = s.warnedFar = true; chime(2); }
    }

    /** {distancia en metros, fracción 0–1 sobre el segmento} del punto p al segmento a–b. */
    private static double[] project(double pla, double plo, double ala, double alo, double bla, double blo) {
        double k = Math.cos(Math.toRadians(pla));
        double ax = alo * k, ay = ala, bx = blo * k, by = bla, px = plo * k, py = pla;
        double dx = bx - ax, dy = by - ay;
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len;
        t = Math.max(0, Math.min(1, t));
        double qx = ax + dx * t, qy = ay + dy * t;
        double m = Math.sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy)) * 111320.0;
        return new double[]{m, t};
    }

    static double dist(double la1, double lo1, double la2, double lo2) {
        double r = 6371000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ───────────────────────── lo que se muestra ─────────────────────────

    Step next() { return state == GUIDING && stepIdx < steps.size() ? steps.get(stepIdx) : null; }
    double toNext() { Step s = next(); return s == null ? -1 : Math.max(0, s.at - along); }
    double remaining() { return lat == null ? -1 : Math.max(0, cum[cum.length - 1] - along); }

    /** Segundos que faltan: el tiempo de OSRM en proporción a lo que queda. */
    double remainingS() {
        if (lat == null || totalM <= 0) return -1;
        return totalS * remaining() / Math.max(1, cum[cum.length - 1]);
    }

    /** Texto del giro (en español; se traduce con I18n). */
    static String instruction(Step s) {
        if (s == null) return "";
        String t = s.type, m = s.modifier;
        if ("arrive".equals(t)) return "Llegaste a tu destino";
        if ("roundabout".equals(t) || "rotary".equals(t) || "roundabout turn".equals(t)) {
            return s.exit > 0 ? "En la glorieta, toma la salida " + s.exit : "Entra a la glorieta";
        }
        if ("exit roundabout".equals(t) || "exit rotary".equals(t)) return "Sal de la glorieta";
        if ("merge".equals(t)) return "Incorpórate a la vía";
        if ("off ramp".equals(t)) return m.contains("left") ? "Toma la salida a la izquierda" : "Toma la salida a la derecha";
        if ("on ramp".equals(t)) return "Toma la entrada";
        if ("fork".equals(t)) return m.contains("left") ? "Mantente a la izquierda" : "Mantente a la derecha";
        if ("uturn".equals(m)) return "Da la vuelta en U";
        if ("sharp right".equals(m)) return "Gira bruscamente a la derecha";
        if ("sharp left".equals(m)) return "Gira bruscamente a la izquierda";
        if ("slight right".equals(m)) return "Gira levemente a la derecha";
        if ("slight left".equals(m)) return "Gira levemente a la izquierda";
        if ("right".equals(m)) return "Gira a la derecha";
        if ("left".equals(m)) return "Gira a la izquierda";
        return "Sigue derecho";
    }

    /** Ángulo de la flecha del giro: 0 = derecho, 90 = derecha, -90 = izquierda, 180 = vuelta en U. */
    static float angle(Step s) {
        if (s == null) return 0;
        String m = s.modifier == null ? "" : s.modifier;
        if ("uturn".equals(m)) return 180;
        if ("sharp right".equals(m)) return 135;
        if ("right".equals(m)) return 90;
        if ("slight right".equals(m)) return 45;
        if ("sharp left".equals(m)) return -135;
        if ("left".equals(m)) return -90;
        if ("slight left".equals(m)) return -45;
        return 0;
    }

    static boolean isRoundabout(Step s) {
        return s != null && s.type != null && (s.type.contains("roundabout") || s.type.contains("rotary"));
    }

    static String distText(double m, boolean mph) {
        if (m < 0) return "";
        if (mph) {
            double mi = m / 1609.344;
            if (mi < 0.1) return Math.round(m * 3.28084 / 10) * 10 + " ft";
            return String.format(Locale.US, mi < 10 ? "%.1f mi" : "%.0f mi", mi);
        }
        if (m < 1000) return (m < 100 ? Math.round(m / 10) * 10 : Math.round(m / 50) * 50) + " m";
        return String.format(Locale.US, m < 10000 ? "%.1f km" : "%.0f km", m / 1000);
    }

    // ───────────────────────── sonido ─────────────────────────

    private ToneGenerator tone;

    /** 1 = aviso con tiempo, 2 = el giro ya, 3 = llegada. */
    private void chime(int kind) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            int t = kind == 1 ? ToneGenerator.TONE_PROP_BEEP : kind == 2 ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_ACK;
            tone.startTone(t, kind == 1 ? 180 : 350);
        } catch (Throwable ignored) { }
    }
}
