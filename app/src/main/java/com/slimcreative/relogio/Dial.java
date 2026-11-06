package com.slimcreative.relogio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;

/**
 * Monta o arquivo de fundo de tela no formato capturado do MActivePro:
 *   16 bytes de cabecalho: [posicao do texto: 00 em cima, 01 embaixo] 00 00 | cor do texto R G B | 00 00 | 360 | 360 | 240 | 240 (u16 little-endian)
 *   imagem 360x360 RGB565 (big-endian), recortada em circulo com fundo preto
 *   miniatura 240x240 RGB565 (big-endian), igual
 */
public final class Dial {
    public static final int SIZE = 360;
    public static final int THUMB = 240;

    private Dial() {}

    /** Recorta o centro da foto num quadrado e aplica a mascara redonda. */
    public static Bitmap round(Bitmap src, int size) {
        int s = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - s) / 2, y = (src.getHeight() - s) / 2;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        Path circle = new Path();
        circle.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
        c.clipPath(circle);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, new Rect(x, y, x + s, y + s), new Rect(0, 0, size, size), p);
        return out;
    }

    public static byte[] build(Bitmap photo, int textColor, int textPosition) {
        Bitmap big = round(photo, SIZE);
        Bitmap small = round(photo, THUMB);
        byte[] out = new byte[16 + SIZE * SIZE * 2 + THUMB * THUMB * 2];
        out[0] = (byte) textPosition;
        out[3] = (byte) Color.red(textColor);
        out[4] = (byte) Color.green(textColor);
        out[5] = (byte) Color.blue(textColor);
        putU16(out, 8, SIZE);
        putU16(out, 10, SIZE);
        putU16(out, 12, THUMB);
        putU16(out, 14, THUMB);
        int off = rgb565(big, out, 16);
        rgb565(small, out, off);
        big.recycle();
        small.recycle();
        return out;
    }

    private static void putU16(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >> 8);
    }

    private static int rgb565(Bitmap bmp, byte[] out, int off) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int c : px) {
            int v = ((Color.red(c) >> 3) << 11) | ((Color.green(c) >> 2) << 5) | (Color.blue(c) >> 3);
            out[off++] = (byte) (v >> 8);
            out[off++] = (byte) v;
        }
        return off;
    }
}
