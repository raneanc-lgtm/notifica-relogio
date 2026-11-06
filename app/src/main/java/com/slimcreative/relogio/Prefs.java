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

    /** Quantas vezes o relogio vibra por mensagem (1 a 3). */
    public static int vibes(Context c) { return Math.max(1, Math.min(3, sp(c).getInt("vibes2", 1))); }

    public static void setVibes(Context c, int n) { sp(c).edit().putInt("vibes2", n).apply(); }

    /** Cor do texto (hora) no fundo personalizado. */
    public static int dialColor(Context c) { return sp(c).getInt("dialColor", 0xFFFFFFFF); }

    public static void setDialColor(Context c, int color) { sp(c).edit().putInt("dialColor", color).apply(); }

    /** Posicao do texto no fundo personalizado: 0 em cima, 1 embaixo. */
    public static int dialPosition(Context c) { return sp(c).getInt("dialPos", 0); }

    public static void setDialPosition(Context c, int pos) { sp(c).edit().putInt("dialPos", pos).apply(); }

    /** 4 alarmes: {ligado, hora, min, dias} ou null. Guardado como texto "l,h,m,d;...". */
    public static int[][] alarms(Context c) {
        String raw = sp(c).getString("alarms", "");
        int[][] out = new int[4][];
        String[] parts = raw.split(";", -1);
        for (int i = 0; i < 4 && i < parts.length; i++) {
            String[] f = parts[i].split(",");
            if (f.length != 4) continue;
            try {
                out[i] = new int[]{Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])};
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static void setAlarms(Context c, int[][] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(';');
            if (a[i] != null) sb.append(a[i][0]).append(',').append(a[i][1]).append(',').append(a[i][2]).append(',').append(a[i][3]);
        }
        sp(c).edit().putString("alarms", sb.toString()).apply();
    }

    public static void setApps(Context c, Set<String> apps) {
        sp(c).edit().putStringSet("apps", new HashSet<>(apps)).apply();
    }
}
