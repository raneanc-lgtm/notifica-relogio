package com.slimcreative.relogio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressLint({"MissingPermission", "SetTextI18n"})
public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#121212");
    private static final int CARD = Color.parseColor("#1E1E1E");
    private static final int ACCENT = Color.parseColor("#25D366");

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView statusTv, notifTv, watchTv;
    private LinearLayout scanList;
    private TextView appsTv;
    private Button scanBtn;
    private boolean scanning;
    private final Map<String, String> found = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView title = text("Notifica Relógio", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        statusTv = text("", 15, ACCENT);
        statusTv.setPadding(0, dp(4), 0, dp(12));
        root.addView(statusTv);

        // 1. acesso as notificacoes
        LinearLayout c1 = card(root, "1. Acesso às notificações");
        notifTv = text("", 14, Color.LTGRAY);
        c1.addView(notifTv);
        c1.addView(button("Abrir configuração", v ->
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));

        // 2. relogio
        LinearLayout c2 = card(root, "2. Relógio");
        watchTv = text("", 14, Color.LTGRAY);
        c2.addView(watchTv);
        scanBtn = button("Procurar relógio", v -> startScan());
        c2.addView(scanBtn);
        scanList = new LinearLayout(this);
        scanList.setOrientation(LinearLayout.VERTICAL);
        c2.addView(scanList);

        // 3. apps
        LinearLayout c3 = card(root, "3. Apps que vibram no relógio");
        appsTv = text("", 14, Color.LTGRAY);
        c3.addView(appsTv);
        c3.addView(button("Escolher apps", v -> showAppsDialog()));

        // 4. vibracao e teste
        LinearLayout c4 = card(root, "4. Vibração e teste");
        c4.addView(text("Vibrações por mensagem", 14, Color.LTGRAY));
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 1; i <= 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(i + "x");
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(16);
            rb.setPadding(0, 0, dp(24), 0);
            rg.addView(rb);
        }
        rg.check(1000 + Prefs.vibes(this));
        rg.setOnCheckedChangeListener((g, id) -> Prefs.setVibes(this, id - 1000));
        c4.addView(rg);
        c4.addView(text("2x e 3x repetem a mensagem no relógio", 13, Color.GRAY));
        c4.addView(button("Encontrar relógio", v -> {
            if (!WatchLink.get(this).findWatch())
                Toast.makeText(this, "O relógio não está conectado", Toast.LENGTH_SHORT).show();
        }));
        c4.addView(button("Mandar teste pro relógio", v -> {
            WatchLink.get(this).sendNotification(Packet.APP_WHATSAPP, "Teste", "Funcionou!");
            Toast.makeText(this, "Enviado", Toast.LENGTH_SHORT).show();
        }));

        // 5. fundo da tela
        LinearLayout c5 = card(root, "5. Fundo da tela do relógio");
        c5.addView(text("Escolha uma foto. O centro dela vira o fundo redondo do relógio. Leva uns 20 segundos.", 14, Color.LTGRAY));
        preview = new ImageView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(160), dp(160));
        plp.topMargin = dp(8);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        preview.setLayoutParams(plp);
        preview.setVisibility(View.GONE);
        c5.addView(preview);
        c5.addView(text("Cor da hora e do texto", 14, Color.LTGRAY));
        int[] colors = {0xFFFFFFFF, 0xFFFFEB3B, 0xFFFF9800, 0xFFF44336, 0xFFC05A7E, 0xFFE91E63,
                0xFF9C27B0, 0xFF3F51B5, 0xFF2196F3, 0xFF00BCD4, 0xFF4CAF50, 0xFF9E9E9E};
        LinearLayout row = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(6);
                c5.addView(row, rlp);
            }
            final int col = colors[i];
            View sw = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            slp.rightMargin = dp(6);
            sw.setLayoutParams(slp);
            sw.setOnClickListener(v -> { Prefs.setDialColor(this, col); paintSwatches(); });
            swatches.add(sw);
            swatchColors.add(col);
            row.addView(sw);
        }
        paintSwatches();
        c5.addView(text("Posição da hora", 14, Color.LTGRAY));
        RadioGroup pos = new RadioGroup(this);
        pos.setOrientation(RadioGroup.HORIZONTAL);
        String[] posNames = {"Em cima", "Embaixo"};
        for (int i = 0; i < posNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(posNames[i]);
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(16);
            rb.setPadding(0, 0, dp(24), 0);
            pos.addView(rb);
        }
        pos.check(2000 + Prefs.dialPosition(this));
        pos.setOnCheckedChangeListener((g, id) -> Prefs.setDialPosition(this, id - 2000));
        c5.addView(pos);
        c5.addView(button("Escolher foto e mandar pro relógio", v -> {
            if (WatchLink.get(this).isSendingDial()) {
                Toast.makeText(this, "Espera terminar o envio atual", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, "Escolha a foto"), PICK_PHOTO);
        }));

        setContentView(sv);
        askPermissions();
    }

    private static final int PICK_PHOTO = 7;
    private ImageView preview;
    private final List<View> swatches = new ArrayList<>();
    private final List<Integer> swatchColors = new ArrayList<>();

    private void paintSwatches() {
        int sel = Prefs.dialColor(this);
        for (int i = 0; i < swatches.size(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(swatchColors.get(i));
            d.setCornerRadius(dp(8));
            if (swatchColors.get(i) == sel) d.setStroke(dp(3), ACCENT);
            else d.setStroke(dp(1), Color.DKGRAY);
            swatches.get(i).setBackground(d);
        }
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        statusTv.setText("Status: Preparando a foto…");
        new Thread(() -> {
            try {
                Bitmap photo = loadScaled(uri, 1080);
                if (photo == null) throw new Exception("não consegui abrir essa foto");
                Bitmap shown = Dial.round(photo, Dial.SIZE);
                byte[] file = Dial.build(photo, Prefs.dialColor(this), Prefs.dialPosition(this));
                photo.recycle();
                h.post(() -> {
                    preview.setImageBitmap(shown);
                    preview.setVisibility(View.VISIBLE);
                    String err = WatchLink.get(this).startDial(file);
                    if (err != null) {
                        statusTv.setText("Status: " + err);
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                h.post(() -> Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Abre a foto ja reduzida (fotos de celular sao enormes). */
    private Bitmap loadScaled(Uri uri, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
        if (b == null) return null;
        int rot = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) {}
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAppsText();
        boolean ok = notifAccess();
        notifTv.setText(ok ? "Liberado ✔" : "Ainda não liberado: toque abaixo e ative \"Notifica Relógio\"");
        notifTv.setTextColor(ok ? ACCENT : Color.parseColor("#FF8A65"));
        String n = Prefs.name(this), mac = Prefs.mac(this);
        watchTv.setText(mac == null ? "Nenhum escolhido" : "Escolhido: " + (n == null ? "?" : n) + " (" + mac + ")");
        WatchLink link = WatchLink.get(this);
        link.setListener(s -> statusTv.setText("Status: " + s));
        if (hasBtPerms()) link.ensureConnected();
    }

    @Override
    protected void onPause() {
        super.onPause();
        WatchLink.get(this).setListener(null);
        stopScan();
    }

    private boolean notifAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(this, NotifService.class);
        return flat != null && flat.contains(me.flattenToString());
    }

    // ---------- permissoes ----------
    private String[] btPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasBtPerms() {
        for (String p : btPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void askPermissions() {
        if (!hasBtPerms()) requestPermissions(btPerms(), 1);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (hasBtPerms()) WatchLink.get(this).ensureConnected();
        else Toast.makeText(this, "Sem a permissão de Bluetooth o app não consegue falar com o relógio", Toast.LENGTH_LONG).show();
    }

    // ---------- busca do relogio ----------
    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            if (name == null) name = d.getName();
            if (TextUtils.isEmpty(name) || found.containsKey(d.getAddress())) return;
            found.put(d.getAddress(), name);
            addFound(d.getAddress(), name);
        }
    };

    private void startScan() {
        if (!hasBtPerms()) { askPermissions(); return; }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            Toast.makeText(this, "Liga o Bluetooth primeiro", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null || scanning) return;
        found.clear();
        scanList.removeAllViews();
        scanList.addView(text("Procurando… (deixa o relógio perto e com a tela acesa)", 13, Color.GRAY));
        scanning = true;
        scanBtn.setEnabled(false);
        sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        h.postDelayed(this::stopScan, 12000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        scanBtn.setEnabled(true);
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothLeScanner sc = bm.getAdapter().getBluetoothLeScanner();
            if (sc != null) sc.stopScan(scanCb);
        } catch (Exception ignored) {}
        if (scanList.getChildCount() == 1 && found.isEmpty()) {
            scanList.removeAllViews();
            scanList.addView(text("Não achei nada. Se o MActivePro ainda estiver conectado no relógio, desinstala ele e tenta de novo.", 13, Color.parseColor("#FF8A65")));
        }
    }

    private void addFound(String mac, String name) {
        if (scanList.getChildCount() == 1 && scanList.getChildAt(0) instanceof TextView
                && !(scanList.getChildAt(0) instanceof Button)) scanList.removeAllViews();
        boolean likely = name.toLowerCase().contains("watch");
        Button b = button((likely ? "⌚ " : "") + name + "  •  " + mac, v -> {
            Prefs.setWatch(this, mac, name);
            stopScan();
            scanList.removeAllViews();
            watchTv.setText("Escolhido: " + name + " (" + mac + ")");
            WatchLink.get(this).disconnect();
            WatchLink.get(this).ensureConnected();
        });
        if (likely) scanList.addView(b, 0); else scanList.addView(b);
    }

    // ---------- lista de apps ----------
    private void updateAppsText() {
        int n = Prefs.apps(this).size();
        appsTv.setText(n == 0 ? "Nenhum app escolhido" : n == 1 ? "1 app escolhido" : n + " apps escolhidos");
    }

    private void showAppsDialog() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(getPackageName())) apps.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        Set<String> sel = new HashSet<>(Prefs.apps(this));
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        Collections.sort(sorted, (a, b) -> {
            boolean sa = sel.contains(a.getKey()), sb = sel.contains(b.getKey());
            if (sa != sb) return sa ? -1 : 1;
            return a.getValue().compareToIgnoreCase(b.getValue());
        });
        String[] names = new String[sorted.size()];
        boolean[] checked = new boolean[sorted.size()];
        for (int k = 0; k < sorted.size(); k++) {
            names[k] = sorted.get(k).getValue();
            checked[k] = sel.contains(sorted.get(k).getKey());
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Apps que vibram no relógio")
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Pronto", (d, w) -> {
                    Set<String> out = new HashSet<>();
                    for (int k = 0; k < checked.length; k++) if (checked[k]) out.add(sorted.get(k).getKey());
                    Prefs.setApps(this, out);
                    updateAppsText();
                })
                .setNeutralButton("Todos", null)
                .setNegativeButton("Nenhum", null)
                .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> setAll(dlg, checked, true));
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> setAll(dlg, checked, false));
        });
        dlg.show();
    }

    private void setAll(AlertDialog dlg, boolean[] checked, boolean on) {
        ListView lv = dlg.getListView();
        for (int k = 0; k < checked.length; k++) {
            checked[k] = on;
            lv.setItemChecked(k, on);
        }
    }

    // ---------- helpers de tela ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        root.addView(c, lp);
        TextView t = text(title, 17, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        return c;
    }
}
