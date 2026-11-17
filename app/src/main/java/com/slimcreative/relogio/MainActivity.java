package com.slimcreative.relogio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
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
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
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
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView statusTv, notifTv, watchTv;
    private LinearLayout scanList;
    private TextView appsTv;
    private TextView aiInfoTv;
    private TextView scanBtn;
    private boolean scanning;
    private final Map<String, String> found = new LinkedHashMap<>();

    // ---------- paleta ----------
    private static final int BG = Color.parseColor("#0E1116");
    private static final int CARD = Color.parseColor("#181D26");
    private static final int FIELD = Color.parseColor("#232A36");
    private static final int ACCENT = Color.parseColor("#3DDC97");
    private static final int TEXT = Color.parseColor("#F2F4F7");
    private static final int MUTED = Color.parseColor("#9AA4B2");
    private static final int WARN = Color.parseColor("#FFB454");
    private static final int BAD = Color.parseColor("#FF6B6B");

    private static final int[] TAB_ICONS = {R.drawable.ic_tab_home, R.drawable.ic_tab_bell, R.drawable.ic_tab_image,
            R.drawable.ic_tab_watch, R.drawable.ic_tab_more};
    private static final String[] TAB_NAMES = {"Início", "Avisos", "Tela", "Relógio", "Mais"};
    private final View[] pages = new View[5];
    private final LinearLayout[] tabs = new LinearLayout[5];
    private View statusDot;
    private TextView notifBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(CARD);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        // cabecalho fixo: nome do app + situacao da conexao
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(12));
        TextView title = text("Notifica Relógio", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(round(CARD, 20));
        pill.setPadding(dp(12), dp(7), dp(14), dp(7));
        statusDot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(8);
        pill.addView(statusDot, dlp);
        statusTv = text("", 14, TEXT);
        pill.addView(statusTv);
        LinearLayout.LayoutParams plp0 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp0.topMargin = dp(8);
        header.addView(pill, plp0);
        screen.addView(header);

        FrameLayout content = new FrameLayout(this);
        screen.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ================= INICIO =================
        LinearLayout home = page(content, 0);

        LinearLayout cWatch = card(home, "Meu relógio", null);
        watchTv = text("", 15, TEXT);
        cWatch.addView(watchTv);
        cWatch.addView(primaryBtn("Reconectar agora", v -> {
            if (Prefs.mac(this) == null) { toast("Escolha o relógio primeiro"); return; }
            WatchLink.get(this).reconnectNow();
        }));
        scanBtn = secondaryBtn("Procurar relógio", v -> startScan());
        cWatch.addView(scanBtn);
        scanList = new LinearLayout(this);
        scanList.setOrientation(LinearLayout.VERTICAL);
        cWatch.addView(scanList);

        LinearLayout cAccess = card(home, "Acesso às notificações", null);
        notifTv = text("", 14, MUTED);
        cAccess.addView(notifTv);
        notifBtn = secondaryBtn("Abrir configuração", v ->
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        cAccess.addView(notifBtn);

        LinearLayout cQuick = card(home, "Atalhos", null);
        cQuick.addView(primaryBtn("Mandar teste pro relógio", v -> {
            WatchLink.get(this).sendNotification(Packet.APP_WHATSAPP, "Teste", "Funcionou!");
            toast("Enviado");
        }));
        cQuick.addView(secondaryBtn("Encontrar relógio", v -> {
            if (!WatchLink.get(this).findWatch()) toast("O relógio não está conectado");
        }));
        cQuick.addView(secondaryBtn("Parar toque do celular", v -> FindPhone.stop(this)));
        cQuick.addView(caption("Pra achar o celular, use a opção no relógio: ele toca alto, até no silencioso."));

        // ================= AVISOS =================
        LinearLayout avisos = page(content, 1);

        LinearLayout cApps = card(avisos, "Apps que avisam no relógio", null);
        appsTv = text("", 15, TEXT);
        cApps.addView(appsTv);
        cApps.addView(primaryBtn("Escolher apps", v -> showAppsDialog()));

        LinearLayout cVib = card(avisos, "Vibrações por mensagem", null);
        cVib.addView(segmented(new String[]{"1x", "2x", "3x"}, Prefs.vibes(this) - 1, i -> Prefs.setVibes(this, i + 1)));
        cVib.addView(caption("2x e 3x repetem a mensagem no relógio."));

        // ================= TELA =================
        LinearLayout tela = page(content, 2);

        LinearLayout cDial = card(tela, "Fundo da tela com foto",
                "O centro da foto vira o fundo redondo do relógio. O envio leva uns 20 segundos.");
        preview = new ImageView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(170), dp(170));
        plp.topMargin = dp(10);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        preview.setLayoutParams(plp);
        preview.setVisibility(View.GONE);
        cDial.addView(preview);
        cDial.addView(label("Cor da hora"));
        int[] colors = {0xFFFFFFFF, 0xFFFFEB3B, 0xFFFF9800, 0xFFF44336, 0xFFC05A7E, 0xFFE91E63,
                0xFF9C27B0, 0xFF3F51B5, 0xFF2196F3, 0xFF00BCD4, 0xFF4CAF50, 0xFF9E9E9E};
        LinearLayout row = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(8);
                cDial.addView(row, rlp);
            }
            final int col = colors[i];
            View sw = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            slp.rightMargin = i % 6 == 5 ? 0 : dp(8);
            sw.setLayoutParams(slp);
            sw.setOnClickListener(v -> { Prefs.setDialColor(this, col); paintSwatches(); });
            swatches.add(sw);
            swatchColors.add(col);
            row.addView(sw);
        }
        paintSwatches();
        cDial.addView(label("Posição da hora"));
        cDial.addView(segmented(new String[]{"Em cima", "Embaixo"}, Prefs.dialPosition(this), i -> Prefs.setDialPosition(this, i)));
        cDial.addView(primaryBtn("Escolher foto e mandar", v -> {
            if (WatchLink.get(this).isSendingDial()) { toast("Espera terminar o envio atual"); return; }
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, "Escolha a foto"), PICK_PHOTO);
        }));
        cDial.addView(caption("O andamento do envio aparece lá em cima, na situação da conexão."));

        // ================= RELOGIO =================
        LinearLayout relogio = page(content, 3);

        LinearLayout cAlarm = card(relogio, "Alarmes", "Até 4. Sem nenhum dia marcado, toca uma vez só.");
        int[][] saved = Prefs.alarms(this);
        for (int i = 0; i < 4; i++) cAlarm.addView(alarmRow(i, saved[i]));
        cAlarm.addView(primaryBtn("Salvar alarmes no relógio", v -> {
            int[][] a = collectAlarms();
            Prefs.setAlarms(this, a);
            toast(WatchLink.get(this).setAlarms(a) ? "Alarmes enviados" : "O relógio não está conectado");
        }));

        LinearLayout cName = card(relogio, "Nome no Bluetooth", null);
        EditText nameEt = field("Ex.: Slim Watch", false);
        cName.addView(nameEt);
        cName.addView(secondaryBtn("Trocar nome", v -> {
            String n = nameEt.getText().toString().trim();
            if (n.isEmpty()) return;
            toast(WatchLink.get(this).setBtName(n)
                    ? "Nome enviado. Pode precisar reiniciar o relógio pra aparecer" : "O relógio não está conectado");
        }));

        LinearLayout cConn = card(relogio, "Conexão", null);
        cConn.addView(switchRow("Manter conexão firme",
                "Manda um sinal ao relógio a cada minuto. Gasta um pouco mais de bateria: só ligue se a conexão ficar caindo.",
                Prefs.keepAlive(this), on -> { Prefs.setKeepAlive(this, on); WatchLink.get(this).applyKeepAlive(); }));

        // ================= MAIS =================
        LinearLayout mais = page(content, 4);

        LinearLayout cAi = card(mais, "IA no relógio (Claude)", "Em montagem. A chave da API da Anthropic fica guardada só neste celular.");
        EditText keyEt = field("sk-ant-...", true);
        keyEt.setText(Prefs.apiKey(this));
        cAi.addView(keyEt);
        cAi.addView(secondaryBtn("Salvar chave", v -> {
            Prefs.setApiKey(this, keyEt.getText().toString().trim());
            toast("Chave salva");
        }));
        EditText askEt = field("Pergunta de teste", false);
        cAi.addView(askEt);
        TextView answerTv = text("", 14, TEXT);
        cAi.addView(primaryBtn("Perguntar ao Claude", v -> {
            String key = Prefs.apiKey(this), q = askEt.getText().toString().trim();
            if (key.isEmpty()) { toast("Salve a chave primeiro"); return; }
            if (q.isEmpty()) return;
            answerTv.setText("Pensando…");
            new Thread(() -> {
                String out;
                try {
                    out = AiBridge.askClaude(key, q);
                    String ans = out;
                    h.post(() -> WatchLink.get(this).sendNotification(Packet.APP_WHATSAPP, "Claude", ans));
                } catch (Exception e) {
                    out = "Erro: " + e.getMessage();
                }
                String show = out;
                h.post(() -> answerTv.setText(show));
            }).start();
        }));
        answerTv.setPadding(0, dp(8), 0, 0);
        cAi.addView(answerTv);
        aiInfoTv = caption("");
        cAi.addView(aiInfoTv);
        cAi.addView(caption("A resposta também vai pro relógio. O áudio da IA do relógio fica em Downloads/NotificaRelogio."));

        LinearLayout cLog = card(mais, "Diagnóstico da conexão", "Últimos eventos da conexão com o relógio.");
        TextView logTv = caption("");
        logTv.setTextIsSelectable(true);
        cLog.addView(secondaryBtn("Atualizar", v -> {
            String t = WatchLink.get(this).logText();
            logTv.setText(t.isEmpty() ? "Nada registrado ainda" : t);
        }));
        cLog.addView(secondaryBtn("Copiar", v -> {
            String t = WatchLink.get(this).logText();
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("conexão", t));
            logTv.setText(t.isEmpty() ? "Nada registrado ainda" : t);
            toast("Copiado");
        }));
        cLog.addView(logTv);

        LinearLayout cIcons = card(mais, "Teste de ícones (temporário)",
                "Manda \"Ícone 0\" até \"Ícone " + WatchLink.ICON_TEST_LAST + "\", um a cada 3 segundos. Grave um vídeo do relógio enquanto roda.");
        TextView iconTest = secondaryBtn("Testar ícones", null);
        iconTest.setOnClickListener(v -> {
            WatchLink link = WatchLink.get(this);
            if (link.isIconTestRunning()) {
                link.stopIconTest();
                iconTest.setText("Testar ícones");
            } else if (!link.isReady()) {
                toast("O relógio não está conectado");
            } else {
                link.startIconTest();
                iconTest.setText("Parar teste de ícones");
            }
        });
        cIcons.addView(iconTest);

        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        TextView about = caption("Notifica Relógio " + ver + " · Slim Creative");
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        mais.addView(about);

        // barra de abas
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(4), dp(6), dp(4), dp(6));
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(6));
            ImageView ic = new ImageView(this);
            ic.setImageResource(TAB_ICONS[i]);
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView nm = text(TAB_NAMES[i], 12, MUTED);
            nm.setGravity(Gravity.CENTER);
            t.addView(ic);
            t.addView(nm);
            t.setOnClickListener(v -> showTab(idx));
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        screen.addView(bar);

        setContentView(screen);
        showTab(b == null ? 0 : b.getInt("tab", 0));
        askPermissions();
    }

    private int currentTab;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", currentTab);
    }

    private void showTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < 5; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean sel = i == idx;
            tabs[i].setBackground(sel ? round(FIELD, 14) : null);
            ((ImageView) tabs[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(sel ? ACCENT : MUTED));
            ((TextView) tabs[i].getChildAt(1)).setTextColor(sel ? ACCENT : MUTED);
            ((TextView) tabs[i].getChildAt(1)).setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    /** Mostra a situacao no cabecalho, com a bolinha na cor certa. */
    private void showStatus(String s) {
        statusTv.setText(s);
        String l = s.toLowerCase();
        int c = WARN;
        if (l.contains("trocado") || l.contains("enviado") || l.contains("terminado") || l.startsWith("conectado") || l.startsWith("enviando") || l.startsWith("testando")) c = ACCENT;
        if (l.contains("erro") || l.contains("falha") || l.contains("desligado") || l.contains("nenhum")
                || l.contains("recusou") || l.contains("caiu") || l.contains("não")) c = BAD;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(c);
        statusDot.setBackground(d);
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
            d.setCornerRadius(dp(12));
            if (swatchColors.get(i) == sel) d.setStroke(dp(3), ACCENT);
            else d.setStroke(dp(1), FIELD);
            swatches.get(i).setBackground(d);
        }
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        showStatus("Preparando a foto…");
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
                        showStatus(err);
                        toast(err);
                    }
                });
            } catch (Exception e) {
                h.post(() -> toast("Erro: " + e.getMessage()));
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
        notifTv.setText(ok ? "Liberado" : "Ainda não liberado. Toque abaixo e ative \"Notifica Relógio\".");
        notifTv.setTextColor(ok ? ACCENT : WARN);
        notifBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        scanBtn.setText(Prefs.mac(this) == null ? "Procurar relógio" : "Trocar de relógio");
        String n = Prefs.name(this), mac = Prefs.mac(this);
        watchTv.setText(mac == null ? "Nenhum relógio escolhido ainda" : (n == null ? "Relógio" : n) + "\n" + mac);
        WatchLink link = WatchLink.get(this);
        link.setListener(s -> { showStatus(s); aiInfoTv.setText("IA: " + AiBridge.lastInfo()); });
        aiInfoTv.setText("IA: " + AiBridge.lastInfo());
        link.setAlarmsListener(this::fillAlarms);
        if (hasBtPerms()) link.ensureConnected();
    }

    @Override
    protected void onPause() {
        super.onPause();
        WatchLink.get(this).setAlarmsListener(null);
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
        List<String> need = new ArrayList<>();
        for (String p : btPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (hasBtPerms()) WatchLink.get(this).ensureConnected();
        else toast("Sem a permissão de Bluetooth o app não consegue falar com o relógio");
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
            toast("Liga o Bluetooth primeiro");
            return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null || scanning) return;
        found.clear();
        scanList.removeAllViews();
        scanList.addView(caption("Procurando… deixa o relógio perto e com a tela acesa."));
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
            TextView none = caption("Não achei nada. Se o MActivePro ainda estiver conectado no relógio, desinstala ele e tenta de novo.");
            none.setTextColor(WARN);
            scanList.addView(none);
        }
    }

    private void addFound(String mac, String name) {
        if (found.size() == 1) scanList.removeAllViews();
        boolean likely = name.toLowerCase().contains("watch");
        TextView b = secondaryBtn((likely ? "⌚ " : "") + name + "  •  " + mac, v -> {
            Prefs.setWatch(this, mac, name);
            stopScan();
            scanList.removeAllViews();
            watchTv.setText(name + "\n" + mac);
            scanBtn.setText("Trocar de relógio");
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

    // ---------- alarmes ----------
    private static final String[] DAY_LETTERS = {"S", "T", "Q", "Q", "S", "S", "D"}; // seg..dom
    private final Switch[] alarmOn = new Switch[4];
    private final TextView[] alarmTime = new TextView[4];
    private final int[][] alarmHm = new int[4][2];
    private final int[] alarmDays = new int[4];
    private final List<List<TextView>> dayViews = new ArrayList<>();

    private LinearLayout alarmRow(int i, int[] a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(FIELD, 14));
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        box.setLayoutParams(blp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text("07:00", 28, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        alarmTime[i] = t;
        t.setOnClickListener(v -> new TimePickerDialog(this, (tp, hh, m) -> {
            alarmHm[i][0] = hh; alarmHm[i][1] = m; paintAlarm(i);
        }, alarmHm[i][0], alarmHm[i][1], true).show());
        top.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch on = tinted(new Switch(this));
        alarmOn[i] = on;
        top.addView(on);
        box.addView(top);

        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        List<TextView> dv = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            final int bit = Packet.ALARM_DAY_BITS[d];
            TextView tv = text(DAY_LETTERS[d], 14, TEXT);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.rightMargin = d == 6 ? 0 : dp(6);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> { alarmDays[i] ^= bit; paintAlarm(i); });
            dv.add(tv);
            days.addView(tv);
        }
        dayViews.add(dv);
        box.addView(days, dlp);
        setAlarm(i, a);
        return box;
    }

    private void setAlarm(int i, int[] a) {
        alarmOn[i].setChecked(a != null && a[0] != 0);
        alarmHm[i][0] = a == null ? 7 : a[1];
        alarmHm[i][1] = a == null ? 0 : a[2];
        alarmDays[i] = a == null ? 0 : a[3];
        paintAlarm(i);
    }

    private void paintAlarm(int i) {
        alarmTime[i].setText(String.format("%02d:%02d", alarmHm[i][0], alarmHm[i][1]));
        for (int d = 0; d < 7; d++) {
            boolean sel = (alarmDays[i] & Packet.ALARM_DAY_BITS[d]) != 0;
            dayViews.get(i).get(d).setBackground(round(sel ? ACCENT : CARD, 17));
            dayViews.get(i).get(d).setTextColor(sel ? BG : MUTED);
        }
    }

    private int[][] collectAlarms() {
        int[][] a = new int[4][];
        for (int i = 0; i < 4; i++)
            a[i] = new int[]{alarmOn[i].isChecked() ? 1 : 0, alarmHm[i][0], alarmHm[i][1], alarmDays[i]};
        return a;
    }

    private void fillAlarms(int[][] a) {
        for (int i = 0; i < 4; i++) setAlarm(i, a[i]);
    }

    // ---------- pecas da tela ----------
    private interface IntCallback { void on(int value); }
    private interface BoolCallback { void on(boolean value); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Texto pequeno de explicacao. */
    private TextView caption(String s) {
        TextView t = text(s, 13, MUTED);
        t.setPadding(0, dp(8), 0, 0);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    /** Titulo de um grupo de opcoes dentro do cartao. */
    private TextView label(String s) {
        TextView t = text(s, 14, MUTED);
        t.setPadding(0, dp(16), 0, 0);
        return t;
    }

    private TextView pillButton(String s, int bg, int fg, View.OnClickListener l) {
        TextView b = text(s, 15, fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        GradientDrawable mask = round(Color.WHITE, 14);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(bg, 14), mask));
        b.setClickable(true);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** Botao principal do cartao (verde). */
    private TextView primaryBtn(String s, View.OnClickListener l) { return pillButton(s, ACCENT, BG, l); }

    /** Botao secundario (cinza). */
    private TextView secondaryBtn(String s, View.OnClickListener l) { return pillButton(s, FIELD, TEXT, l); }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(round(FIELD, 12));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (secret) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    /** Opcoes lado a lado, uma marcada por vez. */
    private LinearLayout segmented(String[] names, int selected, IntCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackground(round(FIELD, 14));
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        box.setLayoutParams(blp);
        final TextView[] items = new TextView[names.length];
        final Runnable[] paint = new Runnable[1];
        final int[] sel = {Math.max(0, Math.min(names.length - 1, selected))};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 15, TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(v -> { sel[0] = idx; paint[0].run(); cb.on(idx); });
            items[i] = t;
            box.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        paint[0] = () -> {
            for (int i = 0; i < items.length; i++) {
                boolean on = i == sel[0];
                items[i].setBackground(on ? round(ACCENT, 11) : null);
                items[i].setTextColor(on ? BG : TEXT);
                items[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        };
        paint[0].run();
        return box;
    }

    private LinearLayout switchRow(String title, String desc, boolean checked, BoolCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(title, 15, TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = tinted(new Switch(this));
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, on) -> cb.on(on));
        top.addView(sw);
        box.addView(top);
        if (desc != null) box.addView(caption(desc));
        return box;
    }

    private Switch tinted(Switch sw) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, MUTED}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{0x663DDC97, 0x55FFFFFF}));
        return sw;
    }

    /** Uma aba: area que rola, com os cartoes dentro. */
    private LinearLayout page(FrameLayout content, int index) {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(20));
        sv.addView(col);
        content.addView(sv);
        pages[index] = sv;
        return col;
    }

    private LinearLayout card(LinearLayout parent, String title, String desc) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 20));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        TextView t = text(title, 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        if (desc != null) {
            TextView d = caption(desc);
            d.setPadding(0, dp(4), 0, 0);
            c.addView(d);
        }
        return c;
    }
}
