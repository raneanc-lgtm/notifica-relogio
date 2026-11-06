package com.slimcreative.relogio;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * IA do relogio, comando 0x85 (SDK NJJBle, EVT_TYPE_APP_RECORDING):
 *  relogio -> celular: 0 "o app tem IA?", 1 inicio/fim da fala [status 0 inicio / 1 fim][sessao], 2 audio [id][dados]
 *  celular -> relogio: 0 resposta "tem" ([0][0]), 3 texto do que foi falado [3][qtd][id][texto], 4 resposta [4][texto]
 * Parte 1: responde que tem IA, guarda o audio em Downloads pra descobrir o formato.
 */
public final class AiBridge {
    static final String TAG = "RelogioIA";
    private static final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    private static int frames;
    private static volatile String lastInfo = "Nenhuma gravação ainda";

    private AiBridge() {}

    public static String lastInfo() { return lastInfo; }

    public static byte[] supportReply() { return Packet.frame(0x85, new byte[]{0, 0}); }

    public static byte[] spokenText(String text) {
        byte[] t = Packet.cut(text.getBytes(StandardCharsets.UTF_8), 180);
        byte[] p = new byte[3 + t.length];
        p[0] = 3; p[1] = 1; p[2] = 0;
        System.arraycopy(t, 0, p, 3, t.length);
        return Packet.frame(0x85, p);
    }

    public static byte[] answerText(String text) {
        byte[] t = Packet.cut(text.getBytes(StandardCharsets.UTF_8), 180);
        byte[] p = new byte[1 + t.length];
        p[0] = 4;
        System.arraycopy(t, 0, p, 1, t.length);
        return Packet.frame(0x85, p);
    }

    /** Dados do quadro 0x85 vindo do relogio. */
    public static synchronized void onWatch(Context ctx, byte[] p) {
        if (p.length < 1) return;
        int type = p[0] & 0xFF;
        Log.i(TAG, "relogio IA tipo " + type + " " + Packet.hex(p.length > 24 ? java.util.Arrays.copyOf(p, 24) : p));
        WatchLink link = WatchLink.get(ctx);
        if (type == 0) {
            link.sendRaw(supportReply());
            lastInfo = "O relógio pediu a IA e o app respondeu que tem";
        } else if (type == 5) {
            lastInfo = "O relógio pediu tradução (" + (p.length > 2 ? (p[1] & 0xFF) + " → " + (p[2] & 0xFF) : "?") + ")";
        } else if (type == 1 && p.length >= 2) {
            int status = p[1] & 0xFF;
            if (status == 0) {
                audio.reset();
                frames = 0;
                lastInfo = "Gravando a tua voz…";
            } else {
                String file = save(ctx);
                lastInfo = "Áudio recebido: " + frames + " pedaços, " + audio.size() + " bytes" + (file != null ? " → " + file : "");
                link.sendRaw(spokenText("Áudio recebido (" + frames + " pedaços)"));
                link.sendRaw(answerText("A IA ainda está em montagem. O áudio foi guardado."));
            }
        } else if (type == 2 && p.length >= 2) {
            // guarda cada pedaco com [tamanho u16][id] na frente, pra eu separar depois
            int n = p.length - 2;
            audio.write(n >> 8); audio.write(n); audio.write(p[1]);
            audio.write(p, 2, n);
            frames++;
        }
    }

    private static String save(Context ctx) {
        if (audio.size() == 0) return null;
        String name = "ia-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".bin";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NotificaRelogio");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) { audio.writeTo(os); }
            return "Downloads/NotificaRelogio/" + name;
        } catch (Exception e) {
            Log.w(TAG, "nao salvou: " + e);
            return null;
        }
    }

    /** Pergunta ao Claude pela API da Anthropic. Roda fora da tela principal. */
    public static String askClaude(String apiKey, String question) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "claude-haiku-5-5");
        body.put("max_tokens", 300);
        body.put("system", "Você é o Claude, respondendo pela tela pequena de um relógio. "
                + "Responda em português do Brasil, em no máximo 2 frases curtas, sem markdown e sem emoji.");
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", question));
        body.put("messages", msgs);

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int r;
        while (in != null && (r = in.read(b)) > 0) buf.write(b, 0, r);
        String resp = buf.toString("UTF-8");
        if (code >= 400) {
            String msg = resp;
            try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) {}
            throw new Exception("API respondeu " + code + ": " + msg);
        }
        JSONArray content = new JSONObject(resp).getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.getJSONObject(i);
            if ("text".equals(part.optString("type"))) sb.append(part.getString("text"));
        }
        return sb.toString().trim();
    }
}
