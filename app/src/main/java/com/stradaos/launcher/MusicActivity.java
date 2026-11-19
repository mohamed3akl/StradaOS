package com.stradaos.launcher;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pantalla del reproductor integrado: a la izquierda lo que suena con sus controles,
 * a la derecha las carpetas con música y, al tocar una, sus canciones.
 */
public class MusicActivity extends Activity implements LocalPlayer.Listener {

    private LocalPlayer player;
    private final Handler h = new Handler();
    private float s = 1;

    private ImageView artIv, playIc;
    private TextView titleTv, artistTv, posTv, durTv, headTv, emptyTv;
    private SeekBar seek;
    private boolean seeking;
    private ListView list;
    private RowAdapter adapter;

    /** null = viendo la lista de carpetas; si no, la carpeta abierta. */
    private String openFolder;
    private List<String> folders = new ArrayList<String>();
    private List<File> tracks = new ArrayList<File>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        player = LocalPlayer.get(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        s = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1024f, Math.min(dm.widthPixels, dm.heightPixels) / 600f);
        build();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 31);
        } else if (!player.isScanned()) rescan();
        else showFolders();
        // si hay algo sonando, abrir directamente su carpeta
        File cur = player.current();
        if (cur != null && cur.getParentFile() != null && player.isScanned()) showFolder(cur.getParent());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        rescan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        player.addListener(this);
        render();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        player.removeListener(this);
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (openFolder != null) showFolders();
        else super.onBackPressed();
    }

    public void onTrack(NowPlaying.Track t) {
        render();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!seeking) updateTime();
            h.postDelayed(this, 500);
        }
    };

    // ───────────────────────── vista ─────────────────────────

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Main.BG);
        int pad = px(16);
        root.setPadding(pad, pad, pad, pad);

        // ── izquierda: lo que suena ──
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
        TextView ttl = tv("Música", 20, Main.TEXT, true);
        ttl.setPadding(I18n.rtl() ? 0 : px(12), 0, I18n.rtl() ? px(12) : 0, 0);
        top.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        left.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        artIv = new ImageView(this);
        artIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artIv.setBackground(round(Main.INNER, 14));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(190), px(190));
        alp.topMargin = px(12);
        left.addView(artIv, alp);

        titleTv = tv("Nada sonando", 18, Main.TEXT, true);
        titleTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = px(12);
        left.addView(titleTv, tlp);
        artistTv = tv("Elige una canción", 14, Main.MUTED, false);
        artistTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = px(4);
        left.addView(artistTv, arlp);

        seek = new SeekBar(this);
        try { seek.getProgressDrawable().setColorFilter(Main.RED, PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) { }
        try { if (Build.VERSION.SDK_INT >= 16) seek.getThumb().setColorFilter(Main.RED, PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) { }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) posTv.setText(fmt(p)); }
            public void onStartTrackingTouch(SeekBar b) { seeking = true; }
            public void onStopTrackingTouch(SeekBar b) { seeking = false; player.seek(b.getProgress()); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(10);
        left.addView(seek, slp);

        LinearLayout times = new LinearLayout(this);
        posTv = tv("0:00", 12, Main.MUTED, false);
        durTv = tv("0:00", 12, Main.MUTED, false);
        durTv.setGravity(Gravity.END);
        times.addView(posTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(durTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.setPadding(px(14), 0, px(14), 0);
        left.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        View prev = circle("skip_previous", 60, 30, Main.BTN);
        FrameLayout play = circle("play_arrow", 76, 38, Main.RED);
        playIc = (ImageView) play.getChildAt(0);
        View next = circle("skip_next", 60, 30, Main.BTN);
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { player.prev(); } });
        play.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { player.toggle(); } });
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { player.next(); } });
        LinearLayout.LayoutParams sm = new LinearLayout.LayoutParams(px(60), px(60));
        LinearLayout.LayoutParams bg = new LinearLayout.LayoutParams(px(76), px(76));
        bg.leftMargin = bg.rightMargin = px(18);
        ctl.addView(prev, sm);
        ctl.addView(play, bg);
        ctl.addView(next, new LinearLayout.LayoutParams(px(60), px(60)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(8);
        left.addView(ctl, clp);

        root.addView(left, new LinearLayout.LayoutParams(px(360), ViewGroup.LayoutParams.MATCH_PARENT));

        // ── derecha: carpetas y canciones ──
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(round(Main.CARD, 18));
        right.setPadding(px(14), px(12), px(14), px(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        headTv = tv("Carpetas", 18, Main.TEXT, true);
        bar.addView(headTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View refresh = circle("restart_alt", 44, 22, Main.BTN);
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { rescan(); } });
        bar.addView(refresh, new LinearLayout.LayoutParams(px(44), px(44)));
        right.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout listBox = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { onRow(pos); }
        });
        listBox.addView(list);
        emptyTv = tv("", 15, Main.MUTED, false);
        emptyTv.setSingleLine(false);
        emptyTv.setGravity(Gravity.CENTER);
        listBox.addView(emptyTv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lblp.topMargin = px(8);
        right.addView(listBox, lblp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        I18n.marginStart(rlp, px(14));
        root.addView(right, rlp);

        I18n.applyDirection(root);
        if (android.os.Build.VERSION.SDK_INT >= 17) ctl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // ⏮ ▶ ⏭ sin reflejar
        setContentView(root);
    }

    private void render() {
        if (titleTv == null) return;
        if (player.hasTrack()) {
            titleTv.setText(player.title());
            artistTv.setText(player.artist() != null ? player.artist() : "");
        } else {
            titleTv.setText(I18n.t("Nada sonando"));
            artistTv.setText(I18n.t("Elige una canción"));
        }
        Bitmap a = player.art();
        if (a != null) { artIv.setImageBitmap(a); artIv.clearColorFilter(); artIv.setPadding(0, 0, 0, 0); }
        else {
            artIv.setImageBitmap(icon("music_note"));
            artIv.setColorFilter(Main.MUTED, PorterDuff.Mode.SRC_IN);
            artIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            artIv.setPadding(px(50), px(50), px(50), px(50));
        }
        if (a != null) artIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        playIc.setImageBitmap(icon(player.isPlaying() ? "pause" : "play_arrow"));
        updateTime();
    }

    private void updateTime() {
        long d = player.duration(), p = player.position();
        if (d > 0) {
            seek.setMax((int) d);
            seek.setProgress((int) Math.max(0, p));
            durTv.setText(fmt(d));
        } else {
            seek.setMax(1);
            seek.setProgress(0);
            durTv.setText("0:00");
        }
        posTv.setText(fmt(Math.max(0, p)));
    }

    // ───────────────────────── lista ─────────────────────────

    private void rescan() {
        emptyTv.setText(I18n.t("Buscando música en la USB, la tarjeta SD y la memoria…"));
        emptyTv.setVisibility(View.VISIBLE);
        folders.clear();
        tracks.clear();
        adapter.notifyDataSetChanged();
        player.scan(new LocalPlayer.ScanCb() { public void onDone() {
            if (isFinishing()) return;
            File cur = player.current();
            if (openFolder != null && player.library().containsKey(openFolder)) showFolder(openFolder);
            else if (cur != null && player.library().containsKey(cur.getParent())) showFolder(cur.getParent());
            else showFolders();
        }});
    }

    private void showFolders() {
        openFolder = null;
        Map<String, List<File>> lib = player.library();
        folders = new ArrayList<String>(lib.keySet());
        tracks = new ArrayList<File>();
        headTv.setText(I18n.t("Carpetas"));
        if (folders.isEmpty() && !player.isScanning()) {
            emptyTv.setText(I18n.t("No encontré música. Conecta una memoria USB o tarjeta SD con canciones (MP3, M4A, FLAC…) y toca ↻."));
            emptyTv.setVisibility(View.VISIBLE);
        } else if (!folders.isEmpty()) emptyTv.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private void showFolder(String folder) {
        List<File> l = player.library().get(folder);
        if (l == null) { showFolders(); return; }
        openFolder = folder;
        tracks = new ArrayList<File>(l);
        headTv.setText(new File(folder).getName());
        emptyTv.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        int at = tracks.indexOf(player.current());
        list.setSelection(at > 2 ? at - 2 : 0);
    }

    private void onRow(int pos) {
        if (openFolder == null) {
            if (pos < folders.size()) showFolder(folders.get(pos));
            return;
        }
        if (pos == 0) { showFolders(); return; }      // fila «‹ Carpetas»
        int i = pos - 1;
        if (i < tracks.size()) player.play(tracks, i);
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return openFolder == null ? folders.size() : tracks.size() + 1; }
        public Object getItem(int p) { return null; }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = cv instanceof LinearLayout ? (LinearLayout) cv : newRow();
            ImageView ic = (ImageView) row.getChildAt(0);
            TextView main = (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(0);
            TextView sub = (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(1);
            int color = Main.TEXT;
            if (openFolder == null) {
                String f = folders.get(pos);
                List<File> l = player.library().get(f);
                ic.setImageBitmap(icon("folder"));
                ic.setScaleX(1);
                main.setText(new File(f).getName());
                sub.setText(I18n.t((l != null ? l.size() : 0) + " canciones") + "  ·  " + f);
                sub.setVisibility(View.VISIBLE);
            } else if (pos == 0) {
                ic.setImageBitmap(icon("arrow_back"));
                ic.setScaleX(I18n.rtl() ? -1 : 1);
                main.setText(I18n.t("Carpetas"));
                sub.setVisibility(View.GONE);
            } else {
                File f = tracks.get(pos - 1);
                boolean cur = f.equals(player.current());
                ic.setImageBitmap(icon(cur && player.isPlaying() ? "play_arrow" : "music_note"));
                ic.setScaleX(1);
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                main.setText(dot > 0 ? n.substring(0, dot) : n);
                sub.setVisibility(View.GONE);
                if (cur) color = Main.RED;
            }
            main.setTextColor(color);
            ic.setColorFilter(color == Main.RED ? Main.RED : Main.ICON, PorterDuff.Mode.SRC_IN);
            return row;
        }

        private LinearLayout newRow() {
            LinearLayout row = new LinearLayout(MusicActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(px(58));
            row.setPadding(px(8), px(6), px(8), px(6));
            ImageView ic = new ImageView(MusicActivity.this);
            row.addView(ic, new LinearLayout.LayoutParams(px(26), px(26)));
            LinearLayout texts = new LinearLayout(MusicActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(I18n.rtl() ? 0 : px(14), 0, I18n.rtl() ? px(14) : 0, 0);
            texts.addView(tv("", 16, Main.TEXT, false));
            texts.addView(tv("", 12, Main.MUTED, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return row;
        }
    }

    // ───────────────────────── utilidades ─────────────────────────

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

    static String fmt(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }
}
