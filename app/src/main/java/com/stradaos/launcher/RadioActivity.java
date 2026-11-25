package com.stradaos.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla de la radio (pantallas con el sintonizador acloud, ver RadioTuner): a la izquierda la emisora que suena con
 * encender/apagar, buscar anterior/siguiente, FM/AM y guardar; a la derecha las emisoras guardadas, en botones grandes
 * para tocarlas manejando. Dejar presionada una guardada la quita.
 */
public class RadioActivity extends Activity {

    private SharedPreferences prefs;
    private float s = 1;

    private TextView freqTv, unitTv, stateTv, bandTv, listHeadTv, emptyTv, nameTv, fineHintTv;
    private ImageView powerIc;
    private FrameLayout powerBtn;
    private GridView grid;
    private final List<Integer> favs = new ArrayList<Integer>();
    private final StationAdapter adapter = new StationAdapter();

    private final BroadcastReceiver status = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String v = null;
            try { v = i.getStringExtra("method_value"); } catch (Throwable ignored) { }
            RadioTuner.onStatus(prefs, v);
            render();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        s = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1024f, Math.min(dm.widthPixels, dm.heightPixels) / 600f);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { registerReceiver(status, new IntentFilter(RadioTuner.STATUS)); } catch (Throwable ignored) { }
        render();
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(status); } catch (Throwable ignored) { }
        super.onPause();
    }

    // ───────────────────────── vista ─────────────────────────

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Main.BG);
        int pad = px(16);
        root.setPadding(pad, pad, pad, pad);

        // ── izquierda: la emisora que suena ──
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setBackground(round(Main.CARD, 18));
        left.setPadding(px(18), px(14), px(18), px(14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View back = circle("arrow_back", 44, 24, Main.BTN);
        if (I18n.rtl()) ((FrameLayout) back).getChildAt(0).setScaleX(-1);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        top.addView(back, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView ttl = tv("Radio", 20, Main.TEXT, true);
        ttl.setPadding(I18n.rtl() ? 0 : px(12), 0, I18n.rtl() ? px(12) : 0, 0);
        top.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // FM / AM: una píldora que muestra la banda y la cambia
        bandTv = tv("FM", 15, Main.TEXT, true);
        bandTv.setGravity(Gravity.CENTER);
        bandTv.setBackground(round(Main.BTN, 22));
        bandTv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            RadioTuner.switchBand(RadioActivity.this, prefs);
            render();
        }});
        top.addView(bandTv, new LinearLayout.LayoutParams(px(150), px(44)));
        left.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // nombre de la emisora (lo pone el usuario al guardarla: el sintonizador no avisa nombres)
        nameTv = tv("", 20, Main.TEXT, true);
        nameTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = px(18);
        left.addView(nameTv, nlp);

        // frecuencia grande (tocarla deja escribir otra)
        freqTv = tv("--", 76, Main.TEXT, true);
        freqTv.setGravity(Gravity.CENTER);
        freqTv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { askFrequency(); } });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = px(8);
        left.addView(freqTv, flp);
        unitTv = tv("MHz", 18, Main.MUTED, false);
        unitTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = px(6);
        left.addView(unitTv, ulp);
        stateTv = tv("", 15, Main.MUTED, false);
        stateTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(14);
        left.addView(stateTv, slp);

        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        // ⏮ guardada · ‹ fina · encender/apagar · fina › · guardada ⏭ (siempre en este orden, también en árabe)
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 17) ctl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        FrameLayout prev = circle("skip_previous", 56, 28, Main.BTN);
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { savedStation(false); } });
        FrameLayout down = circle("chevron_right", 56, 32, Main.INNER);
        ((FrameLayout) down).getChildAt(0).setScaleX(-1);
        fineButton(down, false);
        powerBtn = circle("play_arrow", 80, 40, Main.RED);
        powerIc = (ImageView) powerBtn.getChildAt(0);
        powerBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (RadioTuner.on) RadioTuner.turnOff(RadioActivity.this);
            else if (!RadioTuner.turnOn(RadioActivity.this, prefs)) toast("La radio no respondió");
            render();
        }});
        FrameLayout up = circle("chevron_right", 56, 32, Main.INNER);
        fineButton(up, true);
        FrameLayout next = circle("skip_next", 56, 28, Main.BTN);
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { savedStation(true); } });
        View[] row = {prev, down, powerBtn, up, next};
        for (View b : row) {
            int d = b == powerBtn ? 80 : 56;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(d), px(d));
            lp.leftMargin = lp.rightMargin = px(b == powerBtn ? 8 : 4);
            ctl.addView(b, lp);
        }
        left.addView(ctl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fineHintTv = tv("", 13, Main.MUTED, false);
        fineHintTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fhp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fhp.topMargin = px(10);
        left.addView(fineHintTv, fhp);

        // guardar la emisora · escribir una frecuencia
        LinearLayout acts = new LinearLayout(this);
        TextView save = tv("★  Guardar", 17, Main.TEXT, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(round(Main.BTN, 26));
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveStation(); } });
        acts.addView(save, new LinearLayout.LayoutParams(0, px(52), 1));
        TextView type = tv("Frecuencia", 17, Main.TEXT, true);
        type.setGravity(Gravity.CENTER);
        type.setBackground(round(Main.BTN, 26));
        type.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { askFrequency(); } });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, px(52), 1);
        if (I18n.rtl()) tlp.rightMargin = px(10); else tlp.leftMargin = px(10);
        acts.addView(type, tlp);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svp.topMargin = px(16);
        left.addView(acts, svp);

        // la app de fábrica, por si hace falta (escaneo completo, ajustes de la radio)
        TextView app = tv("Abrir la app de la radio", 14, Main.MUTED, false);
        app.setGravity(Gravity.CENTER);
        app.setPadding(0, px(12), 0, px(4));
        app.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(RadioTuner.PKG);
                if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable e) { toast("No se pudo abrir la app"); }
        }});
        left.addView(app, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(left, new LinearLayout.LayoutParams(px(380), ViewGroup.LayoutParams.MATCH_PARENT));

        // ── derecha: las emisoras guardadas ──
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(round(Main.CARD, 18));
        right.setPadding(px(18), px(16), px(18), px(16));
        listHeadTv = tv("Emisoras guardadas", 18, Main.TEXT, true);
        right.addView(listHeadTv);
        TextView hint = tv("Toca una para escucharla · déjala presionada para cambiarle el nombre o quitarla", 13, Main.MUTED, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = px(6);
        hlp.bottomMargin = px(12);
        right.addView(hint, hlp);

        FrameLayout gridBox = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(px(12));
        grid.setVerticalSpacing(px(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                RadioTuner.tuneTo(RadioActivity.this, prefs, RadioTuner.band(prefs), favs.get(pos));
                render();
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int pos, long id) {
                stationMenu(RadioTuner.band(prefs), favs.get(pos));
                return true;
            }
        });
        gridBox.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyTv = tv("Aún no hay emisoras guardadas.\nBusca una con ‹ › (déjalos presionados para buscar sola) y toca «Guardar».", 16, Main.MUTED, false);
        emptyTv.setSingleLine(false);
        emptyTv.setMaxLines(4);
        emptyTv.setLineSpacing(4 * s, 1f);
        emptyTv.setGravity(Gravity.CENTER);
        gridBox.addView(emptyTv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        right.addView(gridBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (I18n.rtl()) rlp.rightMargin = px(16); else rlp.leftMargin = px(16);
        root.addView(right, rlp);

        setContentView(root);
    }

    private void render() {
        int band = RadioTuner.band(prefs), khz = RadioTuner.khz(prefs);
        boolean fm = band == RadioTuner.FM;
        freqTv.setText(khz > 0 ? RadioTuner.shortLabel(band, khz) : "--");
        unitTv.setText(fm ? "FM · MHz" : "AM · kHz");
        String name = khz > 0 ? RadioTuner.name(prefs, band, khz) : null;
        nameTv.setText(name != null ? name : "");
        nameTv.setVisibility(name != null ? View.VISIBLE : View.INVISIBLE);
        fineHintTv.setText("‹ ›  " + (fm ? "0.1 MHz" : "9 kHz") + " · " + I18n.t("déjalos presionados para buscar"));
        stateTv.setText(I18n.t(!RadioTuner.on ? "Apagada · toca ▶ para escuchar" : RadioTuner.seeking ? "Buscando emisora…" : "Sonando"));
        bandTv.setText(I18n.t(fm ? "Cambiar a AM" : "Cambiar a FM"));
        powerIc.setImageBitmap(icon(RadioTuner.on ? "pause" : "play_arrow"));
        listHeadTv.setText(I18n.t("Emisoras guardadas") + " · " + (fm ? "FM" : "AM"));
        favs.clear();
        favs.addAll(RadioTuner.favorites(prefs, band));
        emptyTv.setVisibility(favs.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class StationAdapter extends BaseAdapter {
        public int getCount() { return favs.size(); }
        public Object getItem(int p) { return favs.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int p, View cv, ViewGroup parent) {
            LinearLayout tile = (LinearLayout) cv;
            if (tile == null) {
                tile = new LinearLayout(RadioActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96)));
                TextView num = tv("", 30, Main.TEXT, true);
                num.setGravity(Gravity.CENTER);
                tile.addView(num);
                TextView unit = tv("", 13, Main.MUTED, false);
                unit.setGravity(Gravity.CENTER);
                unit.setPadding(0, px(4), 0, 0);
                tile.addView(unit);
            }
            int band = RadioTuner.band(prefs), k = favs.get(p);
            boolean cur = k == RadioTuner.khz(prefs);
            tile.setBackground(round(cur ? Main.RED : Main.INNER, 14));
            TextView num = (TextView) tile.getChildAt(0), unit = (TextView) tile.getChildAt(1);
            num.setText(RadioTuner.shortLabel(band, k));
            num.setTextColor(cur ? 0xFFFFFFFF : Main.TEXT);
            String n = RadioTuner.name(prefs, band, k);
            unit.setText(n != null ? n : band == RadioTuner.FM ? "MHz" : "kHz");
            unit.setTextColor(cur ? 0xDDFFFFFF : Main.MUTED);
            return tile;
        }
    }

    // ───────────────────────── acciones ─────────────────────────

    /** ‹ › : un toque sube o baja un paso (0,1 MHz en FM); dejarlo presionado busca sola la siguiente emisora. */
    private void fineButton(View b, final boolean up) {
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            RadioTuner.fine(RadioActivity.this, prefs, up);
            render();
        }});
        b.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
            RadioTuner.step(RadioActivity.this, prefs, up);
            render();
            return true;
        }});
    }

    /** ⏮ ⏭ : emisora guardada anterior / siguiente (sin guardadas, busca sola). */
    private void savedStation(boolean up) {
        if (!RadioTuner.saved(this, prefs, up)) toast("Aún no hay emisoras guardadas: buscando una");
        render();
    }

    /** Guarda la emisora que suena y deja ponerle un nombre (opcional). */
    private void saveStation() {
        final int band = RadioTuner.band(prefs), k = RadioTuner.khz(prefs);
        if (k <= 0) { toast("Busca una emisora con ‹ ›"); return; }
        askName(band, k, true);
    }

    private void askName(final int band, final int k, final boolean saving) {
        final android.widget.EditText et = field();
        et.setSingleLine(true);
        et.setHint(I18n.t("Nombre (opcional), por ejemplo Radio Mix"));
        String old = RadioTuner.name(prefs, band, k);
        if (old != null) { et.setText(old); et.setSelection(old.length()); }
        new TB(this, Main.dlg())
            .setTitle(I18n.t(saving ? "Guardar {}" : "Nombre de {}").replace("{}", RadioTuner.label(band, k)))
            .setView(wrap(et))
            .setPositiveButton("Guardar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    RadioTuner.setName(prefs, band, k, et.getText().toString());
                    if (saving) {
                        RadioTuner.saveCurrent(prefs);
                        toast(I18n.t("Guardada: {}").replace("{}", RadioTuner.title(prefs, band, k)));
                    }
                    render();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** Dejar presionada una guardada: cambiarle el nombre o quitarla. */
    private void stationMenu(final int band, final int k) {
        new TB(this, Main.dlg())
            .setTitle(RadioTuner.title(prefs, band, k))
            .setItems(new CharSequence[]{I18n.t("Cambiar el nombre"), I18n.t("Quitar de guardadas")},
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) { askName(band, k, false); return; }
                        String t = RadioTuner.title(prefs, band, k);
                        RadioTuner.remove(prefs, band, k);
                        toast(I18n.t("Quitada: {}").replace("{}", t));
                        render();
                    }
                })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** Escribir la frecuencia: FM «101.1», AM «1026». */
    private void askFrequency() {
        final int band = RadioTuner.band(prefs);
        final android.widget.EditText et = field();
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint(band == RadioTuner.FM ? "101.1" : "1026");
        new TB(this, Main.dlg())
            .setTitle(I18n.t(band == RadioTuner.FM ? "Frecuencia FM (MHz)" : "Frecuencia AM (kHz)"))
            .setView(wrap(et))
            .setPositiveButton("Sintonizar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    int[] f = RadioTuner.typed(et.getText().toString(), band);
                    if (f == null) { toast("Frecuencia no válida: FM 87.5–108, AM 522–1710"); return; }
                    RadioTuner.tuneTo(RadioActivity.this, prefs, f[0], f[1]);
                    render();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** Campo de texto legible en el tema claro y en el oscuro (como Main.field). */
    private android.widget.EditText field() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setTextColor(Main.LIGHT ? 0xFF1A1A1A : 0xFFFFFFFF);
        et.setHintTextColor(Main.LIGHT ? 0xFF7A808A : 0xFFA0A6B0);
        return et;
    }

    private View wrap(View v) {
        FrameLayout w = new FrameLayout(this);
        int pad = px(20);
        w.setPadding(pad, pad / 2, pad, 0);
        w.addView(v);
        return w;
    }

    // ───────────────────────── utilidades ─────────────────────────

    private void toast(String m) { Toast.makeText(this, I18n.t(m), Toast.LENGTH_SHORT).show(); }

    private int px(float v) { return (int) (v * s); }

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
        if (color != Main.RED) iv.setColorFilter(Main.ICON, PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(ic), px(ic));
        lp.gravity = Gravity.CENTER;
        f.addView(iv, lp);
        return f;
    }

    private final java.util.HashMap<String, Bitmap> icons = new java.util.HashMap<String, Bitmap>();

    private Bitmap icon(String name) {
        Bitmap b = icons.get(name);
        if (b != null) return b;
        try {
            InputStream in = getAssets().open("ic/" + name + ".png");
            b = BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable e) {
            b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
        icons.put(name, b);
        return b;
    }
}
