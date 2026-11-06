package com.slimcreative.relogio;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
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
        MusicBridge.start(this);
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

        // Conversas (WhatsApp etc.): pega quem mandou e o texto da ultima mensagem
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) msgs[msgs.length - 1];
            String sender = str(last.getCharSequence("sender"));
            if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) sender = personName(last.get("sender_person"));
            String msgText = str(last.getCharSequence("text"));
            String convo = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            if (!msgText.isEmpty()) text = msgText;
            if (!sender.isEmpty()) {
                title = (!convo.isEmpty() && !convo.equals(sender)) ? sender + " (" + convo + ")" : sender;
            } else if (!convo.isEmpty()) {
                title = convo;
            }
        }
        // "Fulano (3 mensagens)" -> "Fulano"
        title = title.replaceAll("\\s*\\(\\d+ (mensagens|messages)\\)$", "");
        if (title.isEmpty() && text.isEmpty()) return;

        // evita vibrar 2x pela mesma mensagem
        String key = sbn.getPackageName() + "|" + title + "|" + text;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastTime < 5000) return;
        lastKey = key;
        lastTime = now;

        WatchLink.get(this).sendNotification(Packet.APP_WHATSAPP, title, text);
    }

    private static String personName(Object o) {
        return (o instanceof Person) ? str(((Person) o).getName()) : "";
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }
}
