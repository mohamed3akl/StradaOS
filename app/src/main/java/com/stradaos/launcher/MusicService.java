package com.stradaos.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Mantiene vivo el reproductor integrado mientras suena (servicio en primer plano), para que el sistema
 * no lo cierre al abrir el mapa u otra app. No reproduce nada por sí mismo: eso lo hace LocalPlayer.
 */
public class MusicService extends Service {

    private static final int ID = 4711;
    private static final String CH = "stradaos_music";

    /** Enciende o apaga el servicio según si está sonando. */
    static void update(Context c, boolean playing) {
        Intent i = new Intent(c, MusicService.class);
        try {
            if (playing) c.startService(i);
            else c.stopService(i);
        } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocalPlayer p = LocalPlayer.get(this);
        if (!p.isPlaying()) { stopSelf(); return START_NOT_STICKY; }
        try { startForeground(ID, build(p)); } catch (Throwable ignored) { }
        return START_NOT_STICKY;
    }

    private Notification build(LocalPlayer p) {
        Intent open = new Intent(this, MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int fl = Build.VERSION.SDK_INT >= 23 ? 0x04000000 /* FLAG_IMMUTABLE */ : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, fl | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? O.builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(p.title() != null ? p.title() : "StradaOS")
            .setContentText(p.artist() != null ? p.artist() : I18n.t("Música"))
            .setContentIntent(pi)
            .setOngoing(true);
        return Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** Android 8+: canal de notificación (clase aparte para no tocar estas API en Android 4). */
    private static class O {
        static Notification.Builder builder(Context c, String ch) {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(ch) == null) {
                NotificationChannel nc = new NotificationChannel(ch, I18n.t("Música"), NotificationManager.IMPORTANCE_LOW);
                nc.setShowBadge(false);
                nm.createNotificationChannel(nc);
            }
            return new Notification.Builder(c, ch);
        }
    }
}
