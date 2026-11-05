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
