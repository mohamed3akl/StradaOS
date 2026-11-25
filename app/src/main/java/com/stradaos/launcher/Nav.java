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
 * Con una clave de HERE (Ajustes) la búsqueda y la ruta (con tráfico) se piden primero a HERE; si falla, OpenStreetMap.
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
        /** Pin soltado a mano en el mapa (no viene de la búsqueda). */
        boolean dropped;
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
        int pt;                    // índice del punto de la ruta (rutas de HERE)
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
    static final String PHOTON = "https://photon.komoot.io/api/?limit=10";

    /**
     * HERE (opcional): con la clave de API del usuario (Ajustes → Mapa y clima → Navegación, pref «here_key») la
     * búsqueda y la ruta (con tráfico) se piden a HERE. Si no hay clave o HERE falla, se usa OpenStreetMap como siempre.
     */
    static final String HERE_DISCOVER = "https://discover.search.hereapi.com/v1/discover?limit=10";
    static final String HERE_GEOCODE = "https://geocode.search.hereapi.com/v1/geocode?limit=10";
    static final String HERE_ROUTES = "https://router.hereapi.com/v8/routes?transportMode=car&return=polyline,summary,actions,turnByTurnActions&spans=dynamicSpeedInfo";

    /** Servicio que dio el último resultado: "here" u "osm" (null = aún nada). Para la atribución. */
    static volatile String searchBy, routeBy;
    /** Por qué falló HERE la última vez (se muestra en la navegación y se usó OpenStreetMap). null = funcionó. */
    static volatile String hereError;

    /** Clave de HERE: la de Ajustes; si está vacía, la incluida al compilar (solo en compilaciones propias, ver build.gradle). */
    static String hereKey(Context c) {
        String k = userHereKey(c);
        return k.length() > 0 ? k : builtInHereKey();
    }

    static String userHereKey(Context c) {
        return c.getSharedPreferences("launcher", 0).getString("here_key", "").trim();
    }

    static String builtInHereKey() {
        return BuildConfig.HERE_KEY == null ? "" : BuildConfig.HERE_KEY.trim();
    }

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
    /** Tráfico por tramo de la ruta (0 normal, 1 lento, 2 muy lento); índice i = del punto i al i+1. null = sin datos (OSRM). */
    static volatile byte[] routeLvl;

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
    /** Tiempo sin tráfico de HERE (0 = no se sabe, p. ej. ruta de OSRM). */
    private double totalBaseS;
    private byte[] pendLvl;
    private int matchIdx;                    // segmento donde va el carro
    private double along;                    // metros recorridos sobre la ruta
    private int stepIdx;                     // próximo giro
    private int offCount;
    private long lastReroute;
    static final long TRAFFIC_REFRESH_MS = 5 * 60 * 1000;
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
        final String key = hereKey(ctx);
        new Thread(new Runnable() { public void run() {
            List<Place> out = new ArrayList<Place>();
            String err = null;
            Throwable why = null;
            // 1) coordenadas o enlace de Google Maps pegado: se va directo a ese punto
            Place exact = null;
            try { exact = fromCoords(q); } catch (Throwable ignored) { }
            // 2) con clave de HERE: primero HERE; si falla o no encuentra nada, OpenStreetMap
            List<Place> fromHere = null;
            if (exact == null && key.length() > 0) {
                try { fromHere = hereSearch(q, here, key); hereError = null; } catch (Throwable e) { hereError = "HERE: " + netWhy(e); }
            }
            if (exact != null) {
                out.add(exact);
            } else if (fromHere != null && !fromHere.isEmpty()) {
                out.addAll(fromHere);
                searchBy = "here";
            } else {
                searchBy = "osm";
                // 2) los dos buscadores juntos: Photon tolera errores de escritura, Nominatim es más preciso
                boolean any = false;
                try { out.addAll(nominatim(q, here)); any = true; } catch (Throwable e) { why = e; }
                try { merge(out, photon(q, here)); any = true; } catch (Throwable e) { if (why == null) why = e; }
                if (any) why = null;
            }
            if (why != null) err = "Sin internet: no se pudo buscar. Activa el punto de acceso del celular.\n" + netWhy(why);
            else if (here != null && exact == null && "osm".equals(searchBy)) sortByDistance(out, here);   // HERE ya ordena por cercanía y relevancia
            final List<Place> res = out;
            final String e2 = err;
            h.post(new Runnable() { public void run() { cb.onResult(res, e2); } });
        }}).start();
    }

    /** Agrega los lugares de b que no estén ya en a (mismo sitio: a menos de 150 m). */
    private static void merge(List<Place> a, List<Place> b) {
        for (Place p : b) {
            boolean dup = false;
            for (Place x : a) if (dist(x.lat, x.lon, p.lat, p.lon) < 150) { dup = true; break; }
            if (!dup) a.add(p);
        }
    }

    // ── coordenadas y enlaces de Google Maps ──

    private static final java.util.regex.Pattern[] COORD_RES = {
        java.util.regex.Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),                 // lugar exacto en enlaces largos
        java.util.regex.Pattern.compile("[?&](?:q|ll|query|destination|daddr|center)=(-?\\d+\\.\\d+),\\s*\\+?(-?\\d+\\.\\d+)"),
        java.util.regex.Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),                     // centro del mapa
        java.util.regex.Pattern.compile("(-?\\d{1,2}\\.\\d{3,})\\s*[,;\\s]\\s*(-?\\d{1,3}\\.\\d{3,})"),   // «30.0444, 31.2357»
    };
    private static final java.util.regex.Pattern DMS = java.util.regex.Pattern.compile(
        "(\\d{1,2})°\\s*(\\d{1,2})['′]\\s*([\\d.]+)[\"″]?\\s*([NS])[,\\s]+(\\d{1,3})°\\s*(\\d{1,2})['′]\\s*([\\d.]+)[\"″]?\\s*([EW])");

    /** Lugar a partir de coordenadas o de un enlace de Google Maps (también los cortos maps.app.goo.gl). */
    static Place fromCoords(String raw) throws Exception {
        String t = normalizeDigits(raw.trim());
        if (t.contains("goo.gl/") || t.contains("maps.app")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(t);
            if (m.find()) t = resolveRedirects(m.group());
        }
        if (t.contains("%")) try { t = java.net.URLDecoder.decode(t, "UTF-8"); } catch (Throwable ignored) { }
        double la = Double.NaN, lo = Double.NaN;
        java.util.regex.Matcher d = DMS.matcher(t);
        if (d.find()) {
            la = Integer.parseInt(d.group(1)) + Integer.parseInt(d.group(2)) / 60.0 + Double.parseDouble(d.group(3)) / 3600.0;
            lo = Integer.parseInt(d.group(5)) + Integer.parseInt(d.group(6)) / 60.0 + Double.parseDouble(d.group(7)) / 3600.0;
            if ("S".equals(d.group(4))) la = -la;
            if ("W".equals(d.group(8))) lo = -lo;
        } else {
            for (java.util.regex.Pattern p : COORD_RES) {
                java.util.regex.Matcher m = p.matcher(t);
                if (m.find()) { la = Double.parseDouble(m.group(1)); lo = Double.parseDouble(m.group(2)); break; }
            }
        }
        if (Double.isNaN(la) || Math.abs(la) > 90 || Math.abs(lo) > 180 || (la == 0 && lo == 0)) return null;
        Place p = new Place();
        p.lat = la;
        p.lon = lo;
        p.name = String.format(Locale.US, "%.5f, %.5f", la, lo);
        p.detail = I18n.t("Ubicación por coordenadas");
        return p;
    }

    /** Dígitos árabes (٠١٢…) y la coma árabe a los de siempre. */
    private static String normalizeDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + (c - '\u0660'));
            else if (c >= '\u06F0' && c <= '\u06F9') c = (char) ('0' + (c - '\u06F0'));
            else if (c == '\u060C') c = ',';
            else if (c == '\u066B') c = '.';
            b.append(c);
        }
        return b.toString();
    }

    /** Sigue las redirecciones de un enlace corto hasta encontrar uno con coordenadas (máximo 5 saltos). */
    private static String resolveRedirects(String url) {
        String u = url;
        for (int i = 0; i < 5; i++) {
            java.net.HttpURLConnection c = null;
            try {
                c = Net.open(u, 10000);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code / 100 != 3 || loc == null) return u;
                u = new java.net.URL(new java.net.URL(u), loc).toString();
                if (u.contains("@") || u.contains("!3d") || u.contains("q=")) return u;
            } catch (Throwable e) {
                return u;
            } finally {
                if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
        return u;
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

    /** Buscador de HERE: «discover» cerca de la posición (lugares, negocios y direcciones); sin posición, «geocode». */
    static List<Place> hereSearch(String q, Location here, String key) throws Exception {
        List<Place> out = new ArrayList<Place>();
        StringBuilder u = new StringBuilder(here != null ? HERE_DISCOVER : HERE_GEOCODE);
        u.append("&q=").append(URLEncoder.encode(q, "UTF-8"));
        if (here != null) u.append(String.format(Locale.US, "&at=%.5f,%.5f", here.getLatitude(), here.getLongitude()));
        u.append("&lang=").append(URLEncoder.encode(I18n.lang + "," + Locale.getDefault().getLanguage(), "UTF-8"));
        u.append("&apiKey=").append(URLEncoder.encode(key, "UTF-8"));
        JSONArray items = new JSONObject(new String(Net.get(u.toString(), 15000), "UTF-8")).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            JSONObject pos = o.optJSONObject("position");
            if (pos == null) continue;
            Place p = new Place();
            String title = o.optString("title", "");
            String label = o.optJSONObject("address") != null ? o.getJSONObject("address").optString("label", "") : "";
            p.name = title.length() > 0 ? title : label;
            // la dirección sin repetir el nombre del lugar al principio
            p.detail = label.startsWith(title) && label.length() > title.length() ? label.substring(title.length()).replaceFirst("^[,\\s]+", "") : label.equals(title) ? "" : label;
            p.lat = pos.getDouble("lat");
            p.lon = pos.getDouble("lng");
            out.add(p);
        }
        return out;
    }

    /** Comprueba la clave con una búsqueda pequeña. null = funciona; si no, el motivo. Llamar fuera del hilo principal. */
    static String testHereKey(String key) {
        try {
            Net.get(HERE_GEOCODE + "&q=Berlin&apiKey=" + URLEncoder.encode(key, "UTF-8"), 15000);
            return null;
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("401") || m.contains("403")) return I18n.t("La clave no es válida o no tiene permiso para este servicio");
            if (m.contains("429")) return I18n.t("Se acabó la cuota de la clave");
            return netWhy(e);
        }
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
        routeLvl = null;
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
        final String key = hereKey(ctx);
        new Thread(new Runnable() { public void run() {
            JSONObject r = null;
            Throwable why = null;
            // con clave: HERE (ruta con tráfico); si falla, los servidores OSRM de siempre
            HereRoute parsed = null;
            if (key.length() > 0) {
                try { parsed = hereRoute(from, to, key); hereError = null; } catch (Throwable e) { why = e; hereError = "HERE: " + netWhy(e); }
            }
            final HereRoute hereR = parsed;
            if (hereR == null) for (String base : OSRMS) {
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
                if (route == null && hereR == null) {
                    if (lat == null) { state = ERROR; error = I18n.t("Sin internet: no se pudo calcular la ruta") + "\n" + reason; }
                    fire();                                    // si ya había ruta se sigue con ella
                    return;
                }
                try {
                    if (hereR != null) loadHere(hereR); else load(route);
                    routeBy = hereR != null ? "here" : "osm";
                } catch (Throwable e) { state = ERROR; error = "No se pudo calcular la ruta"; fire(); return; }
                state = GUIDING;
                offCount = 0;
                if (last != null) progress(last);
                fire();
            }});
        }}).start();
    }

    private double[] pendMx, pendMy;

    /** Puntos de la ruta, distancia acumulada y coordenadas para dibujarla (se publican en finishLoad). */
    private void setGeometry(List<double[]> pts) {
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
        pendMx = mx;
        pendMy = my;
    }

    private void finishLoad() {
        matchIdx = 0;
        along = 0;
        stepIdx = 0;
        routeFrom = 0;
        routeMy = pendMy;
        routeMx = pendMx;
        routeLvl = pendLvl;
    }

    /** Ruta de HERE ya leída (en el hilo de red, así un error de formato pasa a OSRM). */
    static class HereRoute {
        final List<double[]> pts = new ArrayList<double[]>();
        final List<Step> steps = new ArrayList<Step>();
        double m, s, base;
        byte[] lvl;
    }

    /** Ruta en carro de HERE Routing v8; el tiempo ya cuenta el tráfico del momento. */
    static HereRoute hereRoute(Location from, Place to, String key) throws Exception {
        StringBuilder u = new StringBuilder(HERE_ROUTES);
        u.append(String.format(Locale.US, "&origin=%.6f,%.6f", from.getLatitude(), from.getLongitude()));
        if (from.hasBearing() && from.hasSpeed() && from.getSpeed() > 2)
            u.append(String.format(Locale.US, ";course=%d", Math.round(from.getBearing()) % 360));
        u.append(String.format(Locale.US, "&destination=%.6f,%.6f", to.lat, to.lon));
        u.append("&apiKey=").append(URLEncoder.encode(key, "UTF-8"));
        JSONObject o = new JSONObject(new String(Net.get(u.toString(), 20000), "UTF-8"));
        JSONArray routes = o.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            JSONArray ns = o.optJSONArray("notices");
            throw new Exception("HERE: " + (ns != null && ns.length() > 0 ? ns.getJSONObject(0).optString("title", "no route") : "no route"));
        }
        JSONArray secs = routes.getJSONObject(0).getJSONArray("sections");
        HereRoute hr = new HereRoute();
        List<Integer> spanAt = new ArrayList<Integer>();
        List<Integer> spanLvl = new ArrayList<Integer>();
        for (int si = 0; si < secs.length(); si++) {
            JSONObject sec = secs.getJSONObject(si);
            List<double[]> p = decodeFlex(sec.getString("polyline"));
            int base = hr.pts.size(), skip = 0;
            if (base > 0 && !p.isEmpty()) { skip = 1; base -= 1; }   // el primer punto repite el último de la sección anterior
            for (int k = skip; k < p.size(); k++) hr.pts.add(p.get(k));
            JSONObject sum = sec.optJSONObject("summary");
            if (sum != null) {
                hr.m += sum.optDouble("length", 0);
                hr.s += sum.optDouble("duration", 0);
                hr.base += sum.optDouble("baseDuration", sum.optDouble("duration", 0));   // sin tráfico
            }
            // tráfico por tramo: velocidad actual contra la normal
            JSONArray spans = sec.optJSONArray("spans");
            if (spans != null) for (int i = 0; i < spans.length(); i++) {
                JSONObject sp = spans.getJSONObject(i);
                JSONObject ds = sp.optJSONObject("dynamicSpeedInfo");
                if (ds == null) continue;
                spanAt.add(base + sp.optInt("offset", 0));
                spanLvl.add(trafficLevel(ds.optDouble("trafficSpeed", 0), ds.optDouble("baseSpeed", 0)));
            }
            JSONArray acts = sec.optJSONArray("turnByTurnActions");
            if (acts == null) acts = sec.optJSONArray("actions");
            if (acts == null) continue;
            for (int i = 0; i < acts.length(); i++) {
                Step st = hereStep(acts, i);
                if (st == null) continue;
                st.pt = base + acts.getJSONObject(i).optInt("offset", 0);
                hr.steps.add(st);
            }
        }
        if (hr.pts.size() < 2) throw new Exception("HERE: empty route");
        for (Step st : hr.steps) st.pt = Math.max(0, Math.min(hr.pts.size() - 1, st.pt));
        if (!spanAt.isEmpty()) {
            // cada tramo de HERE vale desde su punto hasta el inicio del siguiente
            int segs = hr.pts.size() - 1;
            hr.lvl = new byte[segs];
            for (int j = 0; j < spanAt.size(); j++) {
                int a = Math.max(0, Math.min(segs, spanAt.get(j)));
                int b = j + 1 < spanAt.size() ? Math.max(a, Math.min(segs, spanAt.get(j + 1))) : segs;
                for (int k = a; k < b; k++) hr.lvl[k] = (byte) (int) spanLvl.get(j);
            }
        }
        return hr;
    }

    /** Acción de HERE → giro con los mismos tipos que OSRM (así se reutilizan textos y flechas). null = no se anuncia. */
    static Step hereStep(JSONArray acts, int i) throws Exception {
        JSONObject a = acts.getJSONObject(i);
        String act = a.optString("action", ""), dir = a.optString("direction", ""), sev = a.optString("severity", "");
        Step st = new Step();
        st.modifier = "";
        if ("depart".equals(act) || "roundaboutPass".equals(act)) return null;
        if ("arrive".equals(act)) st.type = "arrive";
        else if ("turn".equals(act)) { st.type = "turn"; st.modifier = "middle".equals(dir) ? "straight" : "light".equals(sev) ? "slight " + dir : "heavy".equals(sev) ? "sharp " + dir : dir; }
        else if ("uTurn".equals(act)) { st.type = "turn"; st.modifier = "uturn"; }
        else if ("keep".equals(act)) { if ("middle".equals(dir) || dir.length() == 0) { st.type = "continue"; st.modifier = "straight"; } else { st.type = "fork"; st.modifier = dir; } }
        else if ("roundaboutEnter".equals(act)) {
            st.type = "roundabout";
            st.exit = a.optInt("exit", 0);
            for (int k = i + 1; st.exit == 0 && k < acts.length(); k++) {        // la salida suele venir en roundaboutExit
                JSONObject b = acts.getJSONObject(k);
                if ("roundaboutExit".equals(b.optString("action"))) { st.exit = b.optInt("exit", 0); break; }
            }
        }
        else if ("roundaboutExit".equals(act)) {
            // HERE suele mandar solo «roundaboutExit» con el número de salida (sin «roundaboutEnter»): se anuncia
            // como «En la glorieta, toma la salida N». Si ya se anunció la entrada, solo «Sal de la glorieta».
            boolean entered = i > 0 && "roundaboutEnter".equals(acts.getJSONObject(i - 1).optString("action"));
            st.type = entered ? "exit roundabout" : "roundabout";
            st.exit = entered ? 0 : a.optInt("exit", 0);
        }
        else if ("exit".equals(act)) { st.type = "off ramp"; st.modifier = dir; }
        else if ("enterHighway".equals(act) || "ramp".equals(act)) st.type = "on ramp";
        else { st.type = "continue"; st.modifier = "straight"; }
        st.name = "";
        JSONObject road = a.optJSONObject("nextRoad");
        if (road != null) {
            JSONArray nm = road.optJSONArray("name");
            if (nm == null || nm.length() == 0) nm = road.optJSONArray("number");
            st.name = pickLang(nm);
        }
        return st;
    }

    /** HERE da cada nombre en varios idiomas ([{value, language}]): el del idioma de la app, si no el del sistema, si no el primero. */
    static String pickLang(JSONArray vals) throws Exception {
        if (vals == null || vals.length() == 0) return "";
        String[] want = {I18n.lang, Locale.getDefault().getLanguage()};
        for (String w : want) {
            if (w == null || w.length() == 0) continue;
            for (int k = 0; k < vals.length(); k++) {
                JSONObject v = vals.getJSONObject(k);
                String l = v.optString("language", "");
                if (l.equals(w) || l.startsWith(w + "-")) return v.optString("value", "");
            }
        }
        return vals.getJSONObject(0).optString("value", "");
    }

    private void loadHere(HereRoute hr) {
        setGeometry(hr.pts);
        totalM = hr.m > 0 ? hr.m : cum[cum.length - 1];
        totalS = hr.s;
        totalBaseS = hr.base;
        pendLvl = hr.lvl;
        steps.clear();
        for (Step st : hr.steps) {
            st.lat = lat[st.pt];
            st.lon = lon[st.pt];
            st.at = cum[st.pt];
            steps.add(st);
        }
        finishLoad();
    }

    private static final String FLEX = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** «Flexible polyline» de HERE: versión, encabezado (precisión, tercera dimensión) y luego deltas lat/lon. */
    static List<double[]> decodeFlex(String s) throws Exception {
        int[] pos = {0};
        if (flexU(s, pos) != 1) throw new Exception("HERE: polyline version");
        long hdr = flexU(s, pos);
        double f = Math.pow(10, hdr & 15);
        boolean third = ((hdr >> 4) & 7) != 0;
        List<double[]> out = new ArrayList<double[]>();
        long la = 0, lo = 0;
        while (pos[0] < s.length()) {
            la += flexS(s, pos);
            lo += flexS(s, pos);
            if (third) flexS(s, pos);
            out.add(new double[]{la / f, lo / f});
        }
        return out;
    }

    private static long flexU(String s, int[] pos) throws Exception {
        long r = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= s.length()) throw new Exception("HERE: polyline truncated");
            int c = FLEX.indexOf(s.charAt(pos[0]++));
            if (c < 0) throw new Exception("HERE: polyline char");
            r |= (long) (c & 0x1F) << shift;
            if ((c & 0x20) == 0) return r;
            shift += 5;
        }
    }

    private static long flexS(String s, int[] pos) throws Exception {
        long v = flexU(s, pos);
        return (v & 1) != 0 ? ~(v >> 1) : (v >> 1);
    }

    private void load(JSONObject r) throws Exception {
        List<double[]> pts = decode(r.getString("geometry"));
        int n = pts.size();
        setGeometry(pts);
        totalM = r.optDouble("distance", n > 0 ? cum[n - 1] : 0);
        totalS = r.optDouble("duration", 0);
        totalBaseS = 0;                                   // OSRM no tiene tráfico
        pendLvl = null;
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
        finishLoad();
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
        // ruta de HERE: cada 5 minutos se pide de nuevo desde aquí para tener el tráfico y la llegada al día
        if (state == GUIDING && !routing && hasTraffic() && "here".equals(routeBy)
                && SystemClock.elapsedRealtime() - lastReroute > TRAFFIC_REFRESH_MS) route(l);
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
            routeLvl = null;
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

    /** Nivel de tráfico de un tramo: 0 normal, 1 lento (menos del 70 % de la velocidad normal), 2 muy lento (menos del 40 %). */
    static int trafficLevel(double traffic, double normal) {
        if (traffic <= 0 || normal <= 0) return 0;
        double r = traffic / normal;
        return r < 0.4 ? 2 : r < 0.7 ? 1 : 0;
    }

    /** ¿La ruta tiene datos de tráfico (HERE)? */
    boolean hasTraffic() { return lat != null && totalBaseS > 0; }

    /** Segundos que el tráfico agrega a lo que falta (en proporción a lo que queda). -1 = no se sabe. */
    double trafficDelayS() {
        if (!hasTraffic() || totalS <= 0) return -1;
        return Math.max(0, totalS - totalBaseS) * remaining() / Math.max(1, cum[cum.length - 1]);
    }

    /** Segundos que faltan: el tiempo de la ruta en proporción a lo que queda. */
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
