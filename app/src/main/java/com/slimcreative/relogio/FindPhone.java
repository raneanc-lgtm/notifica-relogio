package com.slimcreative.relogio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** "Encontrar celular": o relogio pede e o celular toca alto (som de alarme) ate alguem parar. */
public final class FindPhone {
    private static final String CHANNEL = "encontrar_celular";
    private static final int NOTIF_ID = 77;
    private static final long MAX_MS = 45000;

    private static MediaPlayer player;
    private static int savedVolume = -1;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private FindPhone() {}

    public static synchronized boolean isRinging() { return player != null; }

    /** O relogio manda o mesmo comando pra comecar e pra parar: alterna. */
    public static synchronized void toggle(Context c) {
        if (player != null) stop(c); else start(c);
    }

    public static synchronized void start(Context c) {
        if (player != null) return;
        Context ctx = c.getApplicationContext();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(ctx, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            player = null;
        }
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0));
        } catch (Exception ignored) {}
        showNotification(ctx);
        main.postDelayed(() -> stop(ctx), MAX_MS);
    }

    public static synchronized void stop(Context c) {
        Context ctx = c.getApplicationContext();
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception ignored) {}
        if (savedVolume >= 0) {
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) {}
            savedVolume = -1;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private static void showNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Encontrar celular", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        PendingIntent stopIntent = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, StopReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("O relógio está procurando o celular")
                .setContentText("Toque em Achei! pra parar")
                .setContentIntent(open)
                .setDeleteIntent(stopIntent)
                .addAction(new Notification.Action.Builder(null, "Achei!", stopIntent).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .build();
        try { nm.notify(NOTIF_ID, n); } catch (Exception ignored) {}
    }

    /** Botao "Achei!" da notificacao. */
    public static class StopReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) { stop(c); }
    }
}
