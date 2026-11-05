package com.slimcreative.relogio;

import java.nio.charset.StandardCharsets;

/**
 * Formato capturado do app MActivePro (log NJJ_BLE):
 *   BC 0B 02 [len] | 0B [app] [tamMsg] "Titulo: mensagem" | [checksum]
 * len      = tamanho do bloco de dados (tudo entre len e checksum)
 * tamMsg   = tamanho em bytes so da mensagem (parte depois de "Titulo: ")
 * checksum = soma dos bytes do bloco de dados (8 bits)
 * app 0x0A = WhatsApp (unico codigo confirmado na captura)
 */
public final class Packet {
    public static final int APP_WHATSAPP = 0x0A;

    private Packet() {}

    /** maxPayload = espaco maximo pro bloco de dados (limitado pelo MTU e por 255). */
    public static byte[] notification(int appCode, String title, String body, int maxPayload) {
        if (title == null) title = "";
        if (body == null) body = "";
        int limit = Math.min(255, maxPayload);
        byte[] prefix = (title + ": ").getBytes(StandardCharsets.UTF_8);
        if (prefix.length > limit - 3 - 4) {
            prefix = cut(prefix, Math.max(0, limit - 3 - 4));
        }
        byte[] msg = body.getBytes(StandardCharsets.UTF_8);
        int room = limit - 3 - prefix.length;
        if (msg.length > room) msg = cut(msg, Math.max(0, room));
        if (msg.length > 255) msg = cut(msg, 255);

        int payloadLen = 3 + prefix.length + msg.length;
        byte[] out = new byte[4 + payloadLen + 1];
        out[0] = (byte) 0xBC;
        out[1] = 0x0B;
        out[2] = 0x02;
        out[3] = (byte) payloadLen;
        int p = 4;
        out[p++] = 0x0B;
        out[p++] = (byte) appCode;
        out[p++] = (byte) msg.length;
        System.arraycopy(prefix, 0, out, p, prefix.length);
        p += prefix.length;
        System.arraycopy(msg, 0, out, p, msg.length);
        p += msg.length;
        int sum = 0;
        for (int i = 4; i < p; i++) sum += out[i] & 0xFF;
        out[p] = (byte) sum;
        return out;
    }

    /** Corta sem quebrar um caractere UTF-8 no meio. */
    static byte[] cut(byte[] src, int max) {
        if (src.length <= max) return src;
        int end = max;
        while (end > 0 && (src[end] & 0xC0) == 0x80) end--;
        byte[] r = new byte[end];
        System.arraycopy(src, 0, r, 0, end);
        return r;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
