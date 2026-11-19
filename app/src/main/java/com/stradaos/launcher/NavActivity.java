package com.stradaos.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Navegación a pantalla completa: el mapa de StradaOS con la ruta, el próximo giro arriba y el tiempo, la distancia
 * y la hora de llegada abajo. Sin ruta activa muestra el buscador de destinos y los recientes.
 */
public class NavActivity extends Activity implements Nav.Listener {

    private Nav nav;
    private float s = 1;
    private LiveMapView map;

    // guía
    private LinearLayout guide, bottom;
    private TurnView turn;
    private TextView distTv, instrTv, streetTv, infoTv;

    // buscador
    private LinearLayout searchBox;
    private View searchFab;
    /** El conductor escondió el buscador para ver todo el mapa. */
    private boolean panelHidden;
    private EditText query;
    private LinearLayout results;
    private TextView statusTv;
    private TextView attrTv;

    /** Atribución según el servicio que se usó de verdad (HERE u OpenStreetMap); antes de usarlo, según la clave. */
    private String attribution() {
        boolean key = Nav.hereKey(this).length() > 0;
        boolean sHere = Nav.searchBy != null ? "here".equals(Nav.searchBy) : key;
        boolean rHere = Nav.routeBy != null ? "here".equals(Nav.routeBy) : key;
        if (sHere && rHere) return "Búsqueda y rutas: HERE · © HERE";
        if (!sHere && !rHere) return "Búsqueda y rutas: Nominatim y OSRM · © colaboradores de OpenStreetMap";
        return I18n.t(sHere ? "Búsqueda: HERE" : "Búsqueda: Nominatim") + " · " + I18n.t(rHere ? "Rutas: HERE" : "Rutas: OSRM")
                + " · " + I18n.t("© HERE y colaboradores de OpenStreetMap");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        nav = Nav.get(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        s = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1024f, Math.min(dm.widthPixels, dm.heightPixels) / 600f);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nav.addListener(this);
        Location l = nav.bestLocation();
        if (l != null) feed(l);
        onNav();
    }

    @Override
    protected void onPause() {
        nav.removeListener(this);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // con ruta activa, «atrás» vuelve al inicio y la guía sigue en la tarjeta del mapa
        super.onBackPressed();
    }

    // ───────────────────────── vista ─────────────────────────

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Main.BG);

        map = new LiveMapView(this, s, 0);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        map.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { map.cycleZoom(); } });

        // ── próximo giro (arriba a la izquierda) ──
        guide = new LinearLayout(this);
        guide.setGravity(Gravity.CENTER_VERTICAL);
        guide.setBackground(round(Main.RED, 18));
        guide.setPadding(px(14), px(12), px(20), px(12));
        turn = new TurnView(this);
        turn.setColor(0xFFFFFFFF);
        guide.addView(turn, new LinearLayout.LayoutParams(px(84), px(84)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(I18n.rtl() ? 0 : px(14), 0, I18n.rtl() ? px(14) : 0, 0);
        distTv = tv("", 34, 0xFFFFFFFF, true);
        instrTv = tv("", 17, 0xFFFFFFFF, true);
        streetTv = tv("", 15, 0xE6FFFFFF, false);
        texts.addView(distTv);
        texts.addView(instrTv, lpTop(4));
        texts.addView(streetTv, lpTop(3));
        guide.addView(texts, new LinearLayout.LayoutParams(px(330), ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.TOP | I18n.left();
        I18n.marginStart(glp, px(14));
        glp.topMargin = px(14);
        root.addView(guide, glp);

        // ── abajo: tiempo, distancia, llegada y terminar ──
        bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(round((Main.CARD & 0x00FFFFFF) | 0xF0000000, 18));
        bottom.setPadding(px(18), px(10), px(10), px(10));
        infoTv = tv("", 18, Main.TEXT, true);
        bottom.addView(infoTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView end = pill("Terminar", Main.RED, 0xFFFFFFFF);
        end.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { nav.stop(); } });
        bottom.addView(end, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(50)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(560), ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | I18n.left();
        I18n.marginStart(blp, px(14));
        blp.bottomMargin = px(14);
        root.addView(bottom, blp);

        // ── arriba a la derecha: volver al inicio ──
        FrameLayout home = circle("arrow_back", 52, 26, (Main.CARD & 0x00FFFFFF) | 0xF0000000);
        home.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(px(52), px(52));
        hlp.gravity = Gravity.TOP | I18n.right();
        hlp.topMargin = px(14);
        I18n.marginEnd(hlp, px(14));
        root.addView(home, hlp);

        // ── buscador ──
        searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.VERTICAL);
        searchBox.setBackground(round((Main.CARD & 0x00FFFFFF) | 0xF5000000, 18));
        searchBox.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ttl = tv("¿A dónde vas?", 20, Main.TEXT, true);
        head.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // esconder el buscador para ver el mapa completo
        FrameLayout hide = circle("arrow_back", 40, 22, Main.BTN);
        hide.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setPanelHidden(true); } });
        head.addView(hide, new LinearLayout.LayoutParams(px(40), px(40)));
        searchBox.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(I18n.t("Lugar, dirección, negocio o coordenadas"));
        query.setTextColor(Main.TEXT);
        query.setHintTextColor(Main.MUTED);
        query.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17 * s);
        query.setBackground(round(Main.INNER, 12));
        query.setPadding(px(14), px(10), px(14), px(10));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) { doSearch(); return true; }
        });
        row.addView(query, new LinearLayout.LayoutParams(0, px(52), 1));
        TextView go = pill("Buscar", Main.RED, 0xFFFFFFFF);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doSearch(); } });
        LinearLayout.LayoutParams golp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52));
        I18n.marginStart(golp, px(10));
        row.addView(go, golp);
        searchBox.addView(row, lpTop(12));
        statusTv = tv("", 14, Main.MUTED, false);
        statusTv.setSingleLine(false);
        searchBox.addView(statusTv, lpTop(10));
        ScrollView sv = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        sv.addView(results);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        svlp.topMargin = px(6);
        searchBox.addView(sv, svlp);
        attrTv = tv(attribution(), 11, Main.MUTED, false);
        searchBox.addView(attrTv, lpTop(6));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(px(470), ViewGroup.LayoutParams.MATCH_PARENT);
        slp.gravity = Gravity.TOP | I18n.left();
        I18n.marginStart(slp, px(14));
        slp.topMargin = slp.bottomMargin = px(14);
        root.addView(searchBox, slp);

        // buscador escondido: un botón redondo arriba a la izquierda lo vuelve a mostrar
        FrameLayout fab = circle("location_on", 60, 30, Main.RED);
        fab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setPanelHidden(false); } });
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(px(60), px(60));
        flp.gravity = Gravity.TOP | I18n.left();
        I18n.marginStart(flp, px(14));
        flp.topMargin = px(14);
        root.addView(fab, flp);
        searchFab = fab;

        I18n.applyDirection(root);
        setContentView(root);
    }

    // ───────────────────────── estado ─────────────────────────

    public void onNav() {
        if (nav.last != null) feed(nav.last);
        if (attrTv != null) attrTv.setText(I18n.t(attribution()));
        boolean searching = nav.state == Nav.IDLE || nav.state == Nav.ERROR || nav.state == Nav.ARRIVED;
        if (!searching) panelHidden = false;          // la próxima búsqueda vuelve a abrir el panel
        searchBox.setVisibility(searching && !panelHidden ? View.VISIBLE : View.GONE);
        searchFab.setVisibility(searching && panelHidden ? View.VISIBLE : View.GONE);
        guide.setVisibility(searching ? View.GONE : View.VISIBLE);
        bottom.setVisibility(searching ? View.GONE : View.VISIBLE);
        if (searching) {
            if (nav.state == Nav.ERROR) statusTv.setText(I18n.lines(nav.error));
            else if (nav.state == Nav.ARRIVED) statusTv.setText(I18n.t("Llegaste a tu destino"));
            if (results.getChildCount() == 0) showRecents();
            return;
        }
        boolean mph = mph(this);
        if (nav.state == Nav.ROUTING) {
            turn.set(null);
            distTv.setText(I18n.t(nav.last == null ? "Esperando señal GPS…" : "Calculando ruta…"));
            instrTv.setText(nav.dest != null ? nav.dest.name : "");
            streetTv.setText("");
            infoTv.setText("");
            return;
        }
        Nav.Step st = nav.next();
        turn.set(st);
        distTv.setText(Nav.distText(nav.toNext(), mph));
        instrTv.setText(I18n.t(Nav.instruction(st)));
        streetTv.setText(st != null && st.name != null ? st.name : "");
        streetTv.setVisibility(st != null && st.name != null && st.name.length() > 0 ? View.VISIBLE : View.GONE);
        infoTv.setText(info(nav, mph, this));
    }

    /** «12 min · 8,4 km · Llegada 14:32» */
    static String info(Nav nav, boolean mph, Context c) {
        double secs = nav.remainingS();
        if (secs < 0) return "";
        long min = Math.max(1, Math.round(secs / 60));
        String t = min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
        String eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(System.currentTimeMillis() + (long) (secs * 1000)));
        return t + "  ·  " + Nav.distText(nav.remaining(), mph) + "  ·  " + I18n.t("Llegada " + eta);
    }

    static boolean mph(Context c) {
        String u = c.getSharedPreferences("launcher", MODE_PRIVATE).getString("speed_unit", "kmh");
        if ("mph".equals(u)) return true;
        if (!"auto".equals(u)) return false;
        String cc = java.util.Locale.getDefault().getCountry();
        return "US".equals(cc) || "GB".equals(cc) || "LR".equals(cc) || "MM".equals(cc);
    }

    private void feed(Location l) {
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        map.setPosition(l.getLatitude(), l.getLongitude(), l.hasBearing() && kmh > 4 ? l.getBearing() : Float.NaN, kmh);
    }

    // ───────────────────────── búsqueda ─────────────────────────

    private void doSearch() {
        final String q = query.getText().toString().trim();
        if (q.length() < 2) return;
        hideKeyboard();
        statusTv.setText(I18n.t("Buscando…"));
        results.removeAllViews();
        nav.search(q, new Nav.SearchCb() { public void onResult(List<Nav.Place> places, String error) {
            if (isFinishing()) return;
            results.removeAllViews();
            attrTv.setText(I18n.t(attribution()));
            if (error != null) { statusTv.setText(I18n.lines(error)); return; }
            if (places.isEmpty()) {
                statusTv.setText(I18n.lines("No encontré ese lugar. Prueba con otro nombre o agrega la ciudad.\n"
                        + "Si está en Google Maps: abre el lugar en el celular, copia sus coordenadas (ej. 30.0444, 31.2357) y escríbelas aquí."));
                return;
            }
            statusTv.setText("");
            for (Nav.Place p : places) addPlace(p, "location_on");
        }});
    }

    private void showRecents() {
        results.removeAllViews();
        List<Nav.Place> r = nav.recents();
        if (r.isEmpty()) return;
        TextView h = tv("Destinos recientes", 13, Main.MUTED, true);
        h.setPadding(px(4), px(8), 0, px(4));
        results.addView(h);
        for (Nav.Place p : r) addPlace(p, "star");
    }

    private void setPanelHidden(boolean hidden) {
        panelHidden = hidden;
        if (hidden) hideKeyboard();
        onNav();
    }

    private void addPlace(final Nav.Place p, String ic) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(60));
        row.setPadding(px(6), px(6), px(6), px(6));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(icon(ic));
        iv.setColorFilter(Main.RED, PorterDuff.Mode.SRC_IN);
        row.addView(iv, new LinearLayout.LayoutParams(px(24), px(24)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(I18n.rtl() ? 0 : px(12), 0, I18n.rtl() ? px(12) : 0, 0);
        TextView a = tv("", 16, Main.TEXT, true);
        a.setText(p.name);
        a.setGravity(I18n.left() | Gravity.CENTER_VERTICAL);
        t.addView(a, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView d = tv("", 12, Main.MUTED, false);
        d.setText(p.detail != null ? p.detail : "");
        d.setGravity(I18n.left() | Gravity.CENTER_VERTICAL);
        d.setVisibility(p.detail != null && p.detail.length() > 0 ? View.VISIBLE : View.GONE);
        t.addView(d, lpTop(3));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Location here = nav.bestLocation();
        if (here != null) {
            TextView km = tv(Nav.distText(Nav.dist(here.getLatitude(), here.getLongitude(), p.lat, p.lon), mph(this)), 13, Main.MUTED, true);
            km.setGravity(I18n.right() | Gravity.CENTER_VERTICAL);
            km.setPadding(px(10), 0, px(10), 0);
            row.addView(km, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView go = pill("Ir", Main.RED, 0xFFFFFFFF);
        row.addView(go, new LinearLayout.LayoutParams(px(64), px(44)));
        View.OnClickListener start = new View.OnClickListener() { public void onClick(View v) {
            hideKeyboard();
            results.removeAllViews();
            statusTv.setText("");
            nav.start(p);
        }};
        row.setOnClickListener(start);
        go.setOnClickListener(start);
        results.addView(row);
    }

    private void hideKeyboard() {
        try {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── utilidades ─────────────────────────

    private int px(float v) { return (int) (v * s); }

    private LinearLayout.LayoutParams lpTop(float m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(m);
        return lp;
    }

    private TextView tv(String t, float size, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(I18n.t(t));
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }

    private TextView pill(String t, int bg, int fg) {
        TextView v = tv(t, 16, fg, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(px(20), 0, px(20), 0);
        v.setBackground(round(bg, 25));
        return v;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * s);
        return g;
    }

    private FrameLayout circle(String name, float d, float ic, int color) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        f.setBackground(g);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(icon(name));
        if (I18n.rtl() && "arrow_back".equals(name)) iv.setScaleX(-1);   // en árabe «volver» apunta a la derecha
        iv.setColorFilter(Main.ICON, PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(ic), px(ic));
        lp.gravity = Gravity.CENTER;
        f.addView(iv, lp);
        return f;
    }

    private Bitmap icon(String name) {
        try {
            InputStream in = getAssets().open("ic/" + name + ".png");
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
