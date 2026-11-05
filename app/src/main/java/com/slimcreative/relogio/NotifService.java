package com.slimcreative.relogio;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Set;

/** O Android chama isso a cada notificacao nova. Nada fica rodando em loop. */
public class NotifService extends NotificationListenerService {
    private String lastKey = "";
    private long lastTime;

    @Override
    public void onListenerConnected() {
        WatchLink.get(this).ensureConnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.isOngoing()) return;
        Set<String> apps = Prefs.apps(this);
        if (!apps.contains(sbn.getPackageName())) return;

        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; // "Novas mensagens: 5"

        Bundle ex = n.extras;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (title.isEmpty() && text.isEmpty()) return;

        // evita vibrar 2x pela mesma mensagem
        String key = sbn.getPackageName() + "|" + title + "|" + text;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastTime < 5000) return;
        lastKey = key;
        lastTime = now;

        WatchLink.get(this).sendNotification(Packet.APP_WHATSAPP, title, text);
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }
}
