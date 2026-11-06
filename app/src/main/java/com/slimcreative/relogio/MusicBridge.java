package com.slimcreative.relogio;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.List;

/**
 * Controle de musica pelo relogio (SDK MusicConst):
 *  relogio -> celular: BC 65 03 .. [1 pausa | 2 toca | 3 proxima | 4 anterior | 5 volume][valor]
 *  celular -> relogio: nome da musica (tipo 4) e volume (tipo 2).
 * So reage a eventos (troca de musica, comando do relogio). Nao fica rodando.
 */
public final class MusicBridge {
    private static MediaController current;
    private static Context appCtx;
    private static String lastName = "";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private MusicBridge() {}

    private static final MediaController.Callback CALLBACK = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata m) { sendName(m); }
    };

    /** Chamado quando o servico de notificacoes conecta: passa a ouvir o player ativo. */
    public static void start(Context c) {
        Context ctx = c.getApplicationContext();
        appCtx = ctx;
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(ctx, NotifService.class);
            msm.addOnActiveSessionsChangedListener(list -> attach(ctx, list), me, main);
            attach(ctx, msm.getActiveSessions(me));
        } catch (Exception ignored) {}
    }

    private static synchronized void attach(Context ctx, List<MediaController> list) {
        MediaController next = (list == null || list.isEmpty()) ? null : list.get(0);
        if (current != null && next != null && current.getSessionToken().equals(next.getSessionToken())) return;
        if (current != null) {
            try { current.unregisterCallback(CALLBACK); } catch (Exception ignored) {}
        }
        current = next;
        if (current != null) {
            current.registerCallback(CALLBACK, main);
            sendName(current.getMetadata());
            sendVolume(ctx);
        }
    }

    private static void sendName(MediaMetadata m) {
        if (m == null) return;
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String name = TextUtils.isEmpty(artist) ? (title == null ? "" : title)
                : (TextUtils.isEmpty(title) ? artist : title + " - " + artist);
        if (name.isEmpty() || name.equals(lastName)) return;
        lastName = name;
        if (appCtx != null) WatchLink.get(appCtx).sendRaw(Packet.musicName(name));
    }

    static void sendVolume(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        WatchLink.get(ctx).sendRaw(Packet.musicVolume(am.getStreamVolume(AudioManager.STREAM_MUSIC),
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
    }

    /** Comando vindo do relogio (dados do quadro 0x65). */
    public static synchronized void onWatchCommand(Context ctx, byte[] payload) {
        if (payload.length < 1) return;
        int type = payload[0] & 0xFF;
        if (type == 5) {
            if (payload.length < 2) return;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.max(0, Math.min(max, payload[1] & 0xFF));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            return;
        }
        if (current == null) return;
        MediaController.TransportControls t = current.getTransportControls();
        if (type == 1) t.pause();
        else if (type == 2) t.play();
        else if (type == 3) t.skipToNext();
        else if (type == 4) t.skipToPrevious();
    }
}
