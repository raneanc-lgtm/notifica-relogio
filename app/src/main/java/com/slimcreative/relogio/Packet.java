package com.slimcreative.relogio;

import java.nio.charset.StandardCharsets;

/**
 * Notificacao (comando 0x0B), formato do SDK oficial da NJJ (CmdMergeImpl.sendMessage),
 * conferido byte a byte com os pacotes do MActivePro:
 *   BC 0B 02 [len] | [icone] [tam. titulo] [tam. texto] "Titulo" ": " "texto" | [checksum]
 * tam. titulo nao conta o ": ". checksum = soma dos bytes de dados (8 bits).
 * Icone 0x0B = WhatsApp neste relogio (o MActivePro mandou 0x0B pro WhatsApp).
 */
public final class Packet {
    public static final int APP_WHATSAPP = 0x0B;
    static final int MAX_TITLE = 60;  // titulo curto pra caber na tela
    static final int MAX_TEXT = 120;  // limite do SDK

    private Packet() {}

    /** maxPayload = espaco maximo pro bloco de dados (limitado pelo MTU e por 255). */
    public static byte[] notification(int icon, String title, String body, int maxPayload) {
        if (title == null) title = "";
        if (body == null || body.trim().isEmpty()) body = " ";
        int limit = Math.min(255, maxPayload);
        byte[] t = cut(title.replace('\n', ' ').getBytes(StandardCharsets.UTF_8), MAX_TITLE);
        byte[] m = cut(body.replace('\n', ' ').getBytes(StandardCharsets.UTF_8), MAX_TEXT);
        // 3 bytes de cabecalho + titulo + ": " + texto tem que caber no pacote
        if (3 + t.length + 2 + m.length > limit) m = cut(m, Math.max(1, limit - 3 - t.length - 2));
        if (3 + t.length + 2 + m.length > limit) t = cut(t, Math.max(0, limit - 3 - 2 - m.length));

        int payloadLen = 3 + t.length + 2 + m.length;
        byte[] out = new byte[4 + payloadLen + 1];
        out[0] = (byte) 0xBC;
        out[1] = 0x0B;
        out[2] = 0x02;
        out[3] = (byte) payloadLen;
        int p = 4;
        out[p++] = (byte) icon;
        out[p++] = (byte) t.length;
        out[p++] = (byte) m.length;
        System.arraycopy(t, 0, out, p, t.length);
        p += t.length;
        out[p++] = 0x3A; // ':'
        out[p++] = 0x20; // ' '
        System.arraycopy(m, 0, out, p, m.length);
        p += m.length;
        int sum = 0;
        for (int i = 4; i < p; i++) sum += out[i] & 0xFF;
        out[p] = (byte) sum;
        return out;
    }

    /** Quadro generico do celular pro relogio: BC [cmd] 02 [len] [dados] [soma dos dados]. */
    public static byte[] frame(int cmd, byte[] payload) {
        byte[] out = new byte[4 + payload.length + 1];
        out[0] = (byte) 0xBC;
        out[1] = (byte) cmd;
        out[2] = 0x02;
        out[3] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        int sum = 0;
        for (byte b : payload) sum += b & 0xFF;
        out[out.length - 1] = (byte) sum;
        return out;
    }

    /** Inicio da troca de tela: BC C8 02 07 | 01 [tamanho u32] [qtd pedacos u16] | soma. */
    public static byte[] dialStart(int size, int chunks) {
        return frame(0xC8, new byte[]{1,
                (byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24),
                (byte) chunks, (byte) (chunks >> 8)});
    }

    /** Pedaco da imagem: BC C9 02 [len] | [numero u16] [ate 230 bytes] | soma. */
    public static byte[] dialChunk(byte[] file, int index) {
        int off = index * DIAL_CHUNK;
        int n = Math.min(DIAL_CHUNK, file.length - off);
        byte[] p = new byte[2 + n];
        p[0] = (byte) index;
        p[1] = (byte) (index >> 8);
        System.arraycopy(file, off, p, 2, n);
        return frame(0xC9, p);
    }

    /** Fim da troca de tela, mandado depois que o relogio confirma: BC CA 02 01 05 05. */
    public static byte[] dialEnd() { return frame(0xCA, new byte[]{5}); }

    public static final int DIAL_CHUNK = 230;

    /** Acerta a hora: BC 43 02 04 [hora local em segundos, u32]. Capturado: 20:30:06 -> 4EE7EC6A. */
    public static byte[] timeSync(long utcMillis, int tzOffsetMillis) {
        long t = (utcMillis + tzOffsetMillis) / 1000L;
        return frame(0x43, new byte[]{(byte) t, (byte) (t >> 8), (byte) (t >> 16), (byte) (t >> 24)});
    }

    /** Encontrar relogio: BC 00 02 01 00 00. */
    public static byte[] findWatch() { return frame(0x00, new byte[]{0}); }

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
