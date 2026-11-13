package com.stradaos.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reproductor de música integrado: archivos de la memoria USB, la tarjeta SD y la memoria interna.
 * Una sola instancia para todo el proceso; avisa a la tarjeta de inicio con NowPlaying.Track (origin = "local").
 * Toda la reproducción ocurre en el hilo principal.
 */
public class LocalPlayer {

    public interface Listener { void onTrack(NowPlaying.Track t); }
    public interface ScanCb { void onDone(); }

    static final String[] EXTS = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".oga", ".opus", ".wav", ".3gp", ".mka"};
    private static final int MAX_DEPTH = 7, MAX_FILES = 8000;

    private static LocalPlayer inst;

    static synchronized LocalPlayer get(Context c) {
        if (inst == null) inst = new LocalPlayer(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final List<Listener> listeners = new ArrayList<Listener>();

    /** Carpeta → canciones (ordenadas). Se llena con scan(). */
    private final LinkedHashMap<String, List<File>> library = new LinkedHashMap<String, List<File>>();
    private boolean scanning, scanned;

    private MediaPlayer mp;
    private List<File> queue = new ArrayList<File>();
    private int index = -1;
    private boolean prepared, playing, wantPlay;
    private String title, artist;
    private Bitmap art;
    private long durMs = -1;

    private LocalPlayer(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("local_player", Context.MODE_PRIVATE);
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    // ───────────────────────── biblioteca ─────────────────────────

    boolean isScanning() { return scanning; }
    boolean isScanned() { return scanned; }

    synchronized Map<String, List<File>> library() { return new LinkedHashMap<String, List<File>>(library); }

    /** Busca música en segundo plano (USB, SD y memoria interna) y avisa al terminar. */
    void scan(final ScanCb cb) {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() { public void run() {
            final LinkedHashMap<String, List<File>> found = new LinkedHashMap<String, List<File>>();
            HashSet<String> seen = new HashSet<String>();
            int[] count = {0};
            for (File root : roots()) walk(root, 0, found, seen, count);
            List<String> keys = new ArrayList<String>(found.keySet());
            Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
            final LinkedHashMap<String, List<File>> sorted = new LinkedHashMap<String, List<File>>();
            for (String k : keys) {
                List<File> l = found.get(k);
                Collections.sort(l, BY_NAME);
                sorted.put(k, l);
            }
            h.post(new Runnable() { public void run() {
                synchronized (LocalPlayer.this) {
                    library.clear();
                    library.putAll(sorted);
                }
                scanning = false;
                scanned = true;
                if (cb != null) cb.onDone();
            }});
        }}).start();
    }

    private static final Comparator<File> BY_NAME = new Comparator<File>() {
        public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
    };

    private List<File> roots() {
        List<File> out = new ArrayList<File>();
        try { out.add(Environment.getExternalStorageDirectory()); } catch (Throwable ignored) { }
        for (String r : new String[]{"/storage", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/usbhost", "/udisk", "/mnt"}) {
            File[] vols = new File(r).listFiles();
            if (vols == null) continue;
            for (File v : vols) if (v.isDirectory() && v.canRead()) out.add(v);
        }
        return out;
    }

    private void walk(File dir, int depth, Map<String, List<File>> found, HashSet<String> seen, int[] count) {
        if (depth > MAX_DEPTH || count[0] >= MAX_FILES || dir == null) return;
        String canon;
        try { canon = dir.getCanonicalPath(); } catch (Throwable e) { return; }
        if (!seen.add(canon)) return;                       // /sdcard y /storage/emulated/0 son lo mismo
        String n = dir.getName();
        if (depth > 0 && (n.startsWith(".") || "Android".equals(n) || "LOST.DIR".equals(n) || "proc".equals(n))) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (count[0] >= MAX_FILES) return;
            if (f.isDirectory()) walk(f, depth + 1, found, seen, count);
            else if (isAudio(f.getName()) && f.length() > 32 * 1024) {
                String key = dir.getAbsolutePath();
                List<File> l = found.get(key);
                if (l == null) { l = new ArrayList<File>(); found.put(key, l); }
                l.add(f);
                count[0]++;
            }
        }
    }

    static boolean isAudio(String name) {
        String l = name.toLowerCase(Locale.US);
        for (String e : EXTS) if (l.endsWith(e)) return true;
        return false;
    }

    // ───────────────────────── reproducción ─────────────────────────

    boolean isPlaying() { return playing; }
    boolean hasTrack() { return index >= 0 && index < queue.size(); }
    File current() { return hasTrack() ? queue.get(index) : null; }
    List<File> queue() { return queue; }
    String title() { return title; }
    String artist() { return artist; }
    Bitmap art() { return art; }
    long duration() { return durMs; }

    long position() {
        try { return prepared && mp != null ? mp.getCurrentPosition() : -1; } catch (Throwable e) { return -1; }
    }

    /** Hay una canción guardada de la última vez (para ▶ en la tarjeta de inicio). */
    boolean canResume() {
        if (hasTrack()) return true;
        String p = prefs.getString("path", null);
        return p != null && new File(p).exists();
    }

    /** Reproduce la canción i de la lista dada (la lista pasa a ser la cola). */
    void play(List<File> list, int i) {
        queue = new ArrayList<File>(list);
        start(i, 0);
    }

    void toggle() {
        if (!hasTrack()) { resumeLast(); return; }
        if (playing) pause(); else resume();
    }

    void next() { if (hasTrack()) start(index + 1 < queue.size() ? index + 1 : 0, 0); }

    /** ◀◀: a los 3 s o más vuelve al inicio de la canción; antes, a la anterior. */
    void prev() {
        if (!hasTrack()) return;
        if (position() > 3000) { seek(0); return; }
        start(index > 0 ? index - 1 : queue.size() - 1, 0);
    }

    void seek(long ms) {
        try { if (prepared && mp != null) mp.seekTo((int) ms); } catch (Throwable ignored) { }
        notifyTrack();
    }

    void pause() {
        wantPlay = false;
        try { if (prepared && mp != null && mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { }
        playing = false;
        save();
        MusicService.update(ctx, false);
        notifyTrack();
    }

    void resume() {
        if (!hasTrack()) { resumeLast(); return; }
        if (!focus()) return;
        wantPlay = true;
        if (prepared && mp != null) {
            try { mp.start(); playing = true; } catch (Throwable e) { playing = false; }
            MusicService.update(ctx, playing);
            notifyTrack();
        } else start(index, prefs.getLong("pos", 0));
    }

    void stop() {
        wantPlay = false;
        save();
        release();
        playing = false;
        MusicService.update(ctx, false);
        NowPlaying.Track t = new NowPlaying.Track();
        t.origin = "local";
        t.clear = true;
        for (Listener l : new ArrayList<Listener>(listeners)) l.onTrack(t);
    }

    /** Vuelve a la última canción guardada (misma carpeta como cola). */
    private void resumeLast() {
        String p = prefs.getString("path", null);
        if (p == null) return;
        File f = new File(p);
        if (!f.exists()) return;
        List<File> list = new ArrayList<File>();
        File[] fs = f.getParentFile() != null ? f.getParentFile().listFiles() : null;
        if (fs != null) for (File x : fs) if (x.isFile() && isAudio(x.getName())) list.add(x);
        Collections.sort(list, BY_NAME);
        if (!list.contains(f)) list.add(0, f);
        queue = list;
        start(list.indexOf(f), prefs.getLong("pos", 0));
    }

    private void start(int i, final long startAt) {
        if (i < 0 || i >= queue.size()) return;
        if (!focus()) return;
        release();
        index = i;
        wantPlay = true;
        final File f = queue.get(i);
        readTags(f);
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer p) {
                if (p != mp) return;
                prepared = true;
                try { durMs = p.getDuration(); } catch (Throwable ignored) { }
                if (startAt > 0 && startAt < durMs - 2000) try { p.seekTo((int) startAt); } catch (Throwable ignored) { }
                if (wantPlay) { p.start(); playing = true; }
                MusicService.update(ctx, playing);
                save();
                notifyTrack();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer p) { if (p == mp) next(); }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer p, int what, int extra) {
                if (p != mp) return true;
                // archivo dañado o formato no soportado por esta pantalla: saltar a la siguiente (sin bucle infinito)
                final int bad = index;
                release();
                if (queue.size() > 1 && bad + 1 < queue.size()) h.postDelayed(new Runnable() { public void run() { start(bad + 1, 0); } }, 300);
                else { playing = false; MusicService.update(ctx, false); notifyTrack(); }
                return true;
            }
        });
        try {
            mp.setDataSource(f.getAbsolutePath());
            mp.prepareAsync();
        } catch (Throwable e) {
            release();
            playing = false;
        }
        notifyTrack();
    }

    private void release() {
        prepared = false;
        durMs = -1;
        if (mp != null) {
            try { mp.reset(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
            mp = null;
        }
    }

    private void readTags(File f) {
        title = null; artist = null; art = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                int sample = 1;
                while (o.outWidth / sample > 512 || o.outHeight / sample > 512) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                art = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            }
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        if (title == null || title.trim().length() == 0) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            title = dot > 0 ? n.substring(0, dot) : n;
        }
        if (artist != null && artist.trim().length() == 0) artist = null;
        if (artist == null && f.getParentFile() != null) artist = f.getParentFile().getName();
    }

    private void save() {
        File f = current();
        if (f == null) return;
        long pos = position();
        prefs.edit().putString("path", f.getAbsolutePath()).putLong("pos", pos > 0 ? pos : 0).apply();
    }

    // ───────────────────────── foco de audio ─────────────────────────

    private boolean hasFocus;
    private boolean pausedByFocus;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                hasFocus = false;
                if (playing) pause();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                // llamada, indicación de navegación larga, reversa…
                if (playing) { pause(); pausedByFocus = true; }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                try { if (mp != null) mp.setVolume(0.3f, 0.3f); } catch (Throwable ignored) { }
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true;
                try { if (mp != null) mp.setVolume(1f, 1f); } catch (Throwable ignored) { }
                if (pausedByFocus) { pausedByFocus = false; resume(); }
            }
        }
    };

    private boolean focus() {
        if (hasFocus) return true;
        int r = am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }

    // ───────────────────────── avisos ─────────────────────────

    void notifyTrack() {
        if (!hasTrack()) return;
        NowPlaying.Track t = new NowPlaying.Track();
        t.origin = "local";
        t.source = "USB";
        t.title = title;
        t.artist = artist;
        t.art = art;
        t.artSet = true;
        t.playing = playing;
        t.durationMs = durMs;
        t.positionMs = position();
        for (Listener l : new ArrayList<Listener>(listeners)) l.onTrack(t);
    }

    /** Abre la pantalla del reproductor. */
    static void openScreen(Context c) {
        Intent i = new Intent(c, MusicActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        c.startActivity(i);
    }
}
