package com.slimcreative.relogio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("relogio", Context.MODE_PRIVATE);
    }

    public static String mac(Context c) { return sp(c).getString("mac", null); }

    public static String name(Context c) { return sp(c).getString("name", null); }

    public static void setWatch(Context c, String mac, String name) {
        sp(c).edit().putString("mac", mac).putString("name", name).apply();
    }

    /** Apps cujas notificacoes vao pro relogio. Padrao: WhatsApp e WhatsApp Business. */
    public static Set<String> apps(Context c) {
        Set<String> def = new HashSet<>();
        def.add("com.whatsapp");
        def.add("com.whatsapp.w4b");
        return new HashSet<>(sp(c).getStringSet("apps", def));
    }

    public static void setApps(Context c, Set<String> apps) {
        sp(c).edit().putStringSet("apps", new HashSet<>(apps)).apply();
    }
}
