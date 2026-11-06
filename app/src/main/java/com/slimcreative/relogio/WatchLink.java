package com.slimcreative.relogio;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.UUID;

/** Conexao BLE com o relogio. Fica parada esperando; o Android reconecta sozinho (autoConnect). */
@SuppressLint("MissingPermission")
public final class WatchLink {
    static final String TAG = "RelogioLink";
    static final UUID SERVICE = UUID.fromString("000001ff-3c17-d293-8e48-14fe2e4da212");
    static final UUID WRITE_CHAR = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener { void onStatus(String status); }

    private static WatchLink instance;

    public static synchronized WatchLink get(Context c) {
        if (instance == null) instance = new WatchLink(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private String connectedMac;
    private int mtu = 23;
    private boolean writing;
    private final ArrayDeque<byte[]> rawQueue = new ArrayDeque<>();

    // troca de tela
    private static final int DIAL_IDLE = 0, DIAL_WAIT_READY = 1, DIAL_SENDING = 2, DIAL_WAIT_END = 3;
    private int dialState = DIAL_IDLE;
    private byte[] dialFile;
    private int dialIndex, dialChunks;
    private volatile String status = "Desconectado";
    private Listener listener;

    private WatchLink(Context c) { ctx = c; }

    public void setListener(Listener l) { listener = l; if (l != null) l.onStatus(status); }

    public String getStatus() { return status; }

    private void setStatus(String s) {
        status = s;
        Log.i(TAG, s);
        final Listener l = listener;
        if (l != null) main.post(() -> l.onStatus(s));
    }

    /** Garante que existe uma conexao (ou pedido de conexao) com o relogio salvo. */
    public synchronized void ensureConnected() {
        String mac = Prefs.mac(ctx);
        if (mac == null) { setStatus("Nenhum relógio escolhido"); return; }
        if (gatt != null && mac.equals(connectedMac)) return;
        disconnect();
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { setStatus("Bluetooth desligado"); return; }
        try {
            BluetoothDevice dev = ad.getRemoteDevice(mac);
            connectedMac = mac;
            setStatus("Procurando o relógio…");
            gatt = dev.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            setStatus("Erro ao conectar: " + e.getMessage());
            gatt = null;
        }
    }

    public synchronized void disconnect() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
        }
        gatt = null;
        writeChar = null;
        connectedMac = null;
        writing = false;
        mtu = 23;
        dialState = DIAL_IDLE;
        dialFile = null;
        rawQueue.clear();
    }

    public synchronized boolean isReady() { return writeChar != null; }

    public synchronized boolean isSendingDial() { return dialState != DIAL_IDLE; }

    /** Manda um arquivo de fundo de tela (montado pelo Dial) pro relogio. */
    public synchronized String startDial(byte[] file) {
        if (writeChar == null || gatt == null) return "O relógio não está conectado";
        if (dialState != DIAL_IDLE) return "Já tem uma imagem sendo enviada";
        if (mtu < 4 + 2 + Packet.DIAL_CHUNK + 1 + 3) return "A conexão Bluetooth não aceitou pacotes grandes (MTU " + mtu + "). Desliga e liga o Bluetooth e tenta de novo";
        dialFile = file;
        dialChunks = (file.length + Packet.DIAL_CHUNK - 1) / Packet.DIAL_CHUNK;
        dialIndex = 0;
        dialState = DIAL_WAIT_READY;
        rawQueue.add(Packet.dialStart(file.length, dialChunks));
        setStatus("Preparando o relógio…");
        final byte[] mine = file;
        main.postDelayed(() -> dialTimeout(mine, DIAL_WAIT_READY, "O relógio não respondeu ao pedido de troca de tela"), 45000);
        pump();
        return null;
    }

    private synchronized void dialTimeout(byte[] file, int state, String msg) {
        if (dialFile == file && dialState == state) {
            dialState = DIAL_IDLE;
            dialFile = null;
            setStatus(msg);
            pump();
        }
    }

    /** Coloca uma notificacao na fila de envio. */
    public synchronized void sendNotification(int app, String title, String body) {
        int times = Prefs.vibes(ctx);
        for (int i = 1; i < times; i++) {
            // repete o aviso pra vibrar mais vezes (o relogio nao deixa vibrar mais forte)
            main.postDelayed(() -> enqueue(app, title, body), 700L * i);
        }
        enqueue(app, title, body);
    }

    // ---------- teste de icones (temporario) ----------
    public static final int ICON_TEST_LAST = 40;
    private int iconTestNext = -1;

    public synchronized boolean isIconTestRunning() { return iconTestNext >= 0; }

    /** Manda "Ícone 0" ate "Ícone 40", um a cada 3 s, cada um com o codigo de icone do numero. */
    public synchronized void startIconTest() {
        if (iconTestNext >= 0) return;
        iconTestNext = 0;
        main.post(this::iconTestStep);
    }

    public synchronized void stopIconTest() { iconTestNext = -1; }

    private synchronized void iconTestStep() {
        if (iconTestNext < 0) return;
        int code = iconTestNext;
        enqueue(code, "Ícone " + code, "Número " + code);
        setStatus("Testando ícones… " + code + " de " + ICON_TEST_LAST);
        if (code >= ICON_TEST_LAST) {
            iconTestNext = -1;
            main.postDelayed(() -> setStatus("Teste de ícones terminado ✔"), 1500);
        } else {
            iconTestNext = code + 1;
            main.postDelayed(this::iconTestStep, 3000);
        }
    }

    private synchronized void enqueue(int app, String title, String body) {
        queue.add(new String[]{String.valueOf(app), title, body});
        while (queue.size() > 10) queue.poll();
        ensureConnected();
        pump();
    }

    private synchronized void pump() {
        if (writing || writeChar == null || gatt == null) return;
        byte[] pkt;
        boolean chunk = false;
        if (!rawQueue.isEmpty()) {
            pkt = rawQueue.peek();
            lastPkt = null;
        } else if (dialState == DIAL_SENDING) {
            pkt = Packet.dialChunk(dialFile, dialIndex);
            chunk = true;
            lastPkt = null;
        } else if (dialState == DIAL_IDLE && !queue.isEmpty()) {
            String[] n = queue.poll();
            pkt = Packet.notification(Integer.parseInt(n[0]), n[1], n[2], mtu - 3 - 5);
            lastPkt = n;
        } else {
            return;
        }
        writeChar.setWriteType(noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writeChar.setValue(pkt);
        writing = gatt.writeCharacteristic(writeChar);
        if (writing) {
            if (chunk) {
                dialIndex++;
                if (dialIndex % 40 == 0 || dialIndex == dialChunks)
                    setStatus("Enviando a imagem… " + (dialIndex * 100 / dialChunks) + "%");
                if (dialIndex >= dialChunks) {
                    dialState = DIAL_WAIT_END;
                    final byte[] mine = dialFile;
                    main.postDelayed(() -> dialTimeout(mine, DIAL_WAIT_END, "O relógio não confirmou a imagem"), 20000);
                }
            } else if (lastPkt == null) {
                rawQueue.poll();
            }
            if (!chunk) Log.i(TAG, "envio " + Packet.hex(pkt));
        } else {
            if (lastPkt != null) queue.addFirst(lastPkt);
            main.postDelayed(this::pump, chunk ? 20 : 500);
        }
    }

    /** Respostas do relogio: BC [cmd] 03 [len] [dados] [soma]. */
    private synchronized void onWatchData(byte[] v) {
        if (v == null || v.length < 5 || (v[0] & 0xFF) != 0xBC) return;
        int cmd = v[1] & 0xFF;
        int len = v[3] & 0xFF;
        byte[] payload = new byte[Math.min(len, v.length - 4)];
        System.arraycopy(v, 4, payload, 0, payload.length);
        if (cmd == 0x5A && payload.length >= 21 && (payload[0] & 0xFF) == 0xFF) {
            // alarmes gravados no relogio: 4 x [ligado][tipo][dias][hora][min]
            int[][] a = new int[4][];
            for (int i = 0; i < 4; i++) {
                int o = 1 + i * 5;
                int type = payload[o + 1] & 0xFF;
                if ((payload[o + 3] & 0xFF) > 23 || (payload[o + 4] & 0xFF) > 59 || type > 3) return; // formato inesperado
                a[i] = type == 0 ? null : new int[]{payload[o] & 0xFF, payload[o + 3] & 0xFF, payload[o + 4] & 0xFF,
                        type == 1 ? 0 : payload[o + 2] & 0xFF};
            }
            Prefs.setAlarms(ctx, a);
            final AlarmsListener l = alarmsListener;
            if (l != null) main.post(() -> l.onAlarms(a));
            return;
        }
        if (v[2] != 0x03) return;
        if (cmd == 0x6A) { main.post(() -> FindPhone.toggle(ctx)); return; }
        if (cmd == 0x65) { main.post(() -> MusicBridge.onWatchCommand(ctx, payload)); return; }
        if (cmd == 0x72 && dialState == DIAL_WAIT_READY) {
            setStatus("Preparando o relógio… " + (v[4] & 0xFF));
        } else if (cmd == 0xC8 && dialState == DIAL_WAIT_READY) {
            dialState = DIAL_SENDING;
            dialIndex = 0;
            setStatus("Enviando a imagem… 0%");
            pump();
        } else if (cmd == 0xCA && dialState == DIAL_WAIT_END) {
            int result = v[4] & 0xFF;
            dialState = DIAL_IDLE;
            dialFile = null;
            if (result == 0) {
                rawQueue.add(Packet.dialEnd());
                setStatus("Fundo da tela trocado ✔");
            } else {
                setStatus("O relógio recusou a imagem (código " + result + ")");
            }
            pump();
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Conectado, preparando…");
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasDial;
                main.removeCallbacks(heartbeat);
                synchronized (WatchLink.this) {
                    writeChar = null; writing = false; mtu = 23;
                    wasDial = dialState != DIAL_IDLE;
                    dialState = DIAL_IDLE; dialFile = null; rawQueue.clear();
                }
                setStatus(wasDial ? "A conexão caiu no meio do envio da imagem. Tenta de novo"
                        : "Relógio fora de alcance (reconecta sozinho)");
                if (st != BluetoothGatt.GATT_SUCCESS && g == gatt) {
                    // erro de conexao: fecha e tenta de novo daqui a pouco
                    synchronized (WatchLink.this) { disconnect(); }
                    main.postDelayed(WatchLink.this::ensureConnected, 15000);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int st) {
            if (st == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService s = g.getService(SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(WRITE_CHAR);
            if (c == null) { setStatus("Esse aparelho não parece ser o relógio certo"); return; }
            // Liga os canais de resposta do relogio (o MActivePro faz isso ao conectar).
            int wp = c.getProperties();
            noResponse = (wp & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    || (wp & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
            Log.i(TAG, "ff02 propriedades=0x" + Integer.toHexString(wp));
            synchronized (WatchLink.this) {
                pendingCccd.clear();
                for (BluetoothGattService sv : g.getServices()) {
                    for (BluetoothGattCharacteristic ch : sv.getCharacteristics()) {
                        int p = ch.getProperties();
                        boolean notify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                        boolean indicate = (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                        if (!notify && !indicate) continue;
                        if (!sv.getUuid().equals(SERVICE) && !ch.getUuid().toString().startsWith("0000ff")) continue;
                        BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
                        if (d == null) continue;
                        g.setCharacteristicNotification(ch, true);
                        d.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        pendingCccd.add(d);
                    }
                }
                pendingWriteChar = c;
            }
            setStatus("Ligando canais do relógio…");
            nextCccd(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            Log.i(TAG, "canal " + d.getCharacteristic().getUuid() + " status=" + st);
            nextCccd(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            Log.i(TAG, "relogio -> " + (v == null ? "" : Packet.hex(v)));
            gotReply = true;
            onWatchData(v);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            synchronized (WatchLink.this) { writing = false; }
            if (st == BluetoothGatt.GATT_WRITE_NOT_PERMITTED && !retried && lastPkt != null) {
                // o canal nao aceitou esse tipo de escrita: troca o tipo e reenvia
                synchronized (WatchLink.this) { noResponse = !noResponse; retried = true; queue.addFirst(lastPkt); }
                pump();
                return;
            }
            retried = false;
            boolean dial;
            synchronized (WatchLink.this) { dial = dialState != DIAL_IDLE; }
            if (st != BluetoothGatt.GATT_SUCCESS) setStatus("Falha no envio (código " + st + ")");
            else if (!dial && lastPkt != null) setStatus("Enviado pro relógio ✔");
            pump();
        }
    };

    private final ArrayDeque<BluetoothGattDescriptor> pendingCccd = new ArrayDeque<>();
    private BluetoothGattCharacteristic pendingWriteChar;
    private volatile boolean gotReply;
    private int cccdTries;
    private boolean noResponse;
    private boolean retried;
    private String[] lastPkt;

    private void nextCccd(BluetoothGatt g) {
        BluetoothGattDescriptor d;
        synchronized (this) { d = pendingCccd.peek(); }
        if (d != null) {
            if (g.writeDescriptor(d)) {
                synchronized (this) { pendingCccd.poll(); }
            } else if (++cccdTries < 20) {
                main.postDelayed(() -> nextCccd(g), 300);
            } else {
                synchronized (this) { pendingCccd.poll(); }
                cccdTries = 0;
                nextCccd(g);
            }
            return;
        }
        cccdTries = 0;
        synchronized (this) {
            if (pendingWriteChar == null) return;
            writeChar = pendingWriteChar;
            pendingWriteChar = null;
            writing = false;
        }
        synchronized (this) {
            long now = System.currentTimeMillis();
            rawQueue.add(Packet.timeSync(now, java.util.TimeZone.getDefault().getOffset(now)));
        }
        setStatus("Conectado ✔");
        main.removeCallbacks(heartbeat);
        if (Prefs.keepAlive(ctx)) main.postDelayed(heartbeat, 60000);
        rawQueue.add(Packet.alarmsRead());
        main.post(() -> MusicBridge.sendVolume(ctx));
        pump();
    }

    /** "To vivo" a cada 60 s enquanto conectado (igual o MActivePro). */
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            synchronized (WatchLink.this) {
                if (writeChar == null || gatt == null || !Prefs.keepAlive(ctx)) return;
                rawQueue.add(Packet.heartbeat());
                pump();
            }
            main.postDelayed(this, 60000);
        }
    };

    /** Manda um quadro pronto, se o relogio estiver conectado (senao descarta). */
    public synchronized void sendRaw(byte[] pkt) {
        if (writeChar == null || gatt == null) return;
        rawQueue.add(pkt);
        pump();
    }

    /** Liga/desliga o "to vivo" na hora. */
    public synchronized void applyKeepAlive() {
        main.removeCallbacks(heartbeat);
        if (Prefs.keepAlive(ctx) && writeChar != null) main.postDelayed(heartbeat, 60000);
    }

    public interface AlarmsListener { void onAlarms(int[][] alarms); }
    private AlarmsListener alarmsListener;
    public void setAlarmsListener(AlarmsListener l) { alarmsListener = l; }

    public synchronized boolean setAlarms(int[][] alarms) {
        if (writeChar == null || gatt == null) return false;
        rawQueue.add(Packet.alarms(alarms));
        pump();
        return true;
    }

    public synchronized boolean setBtName(String name) {
        if (writeChar == null || gatt == null) return false;
        rawQueue.add(Packet.btName(name));
        pump();
        return true;
    }

    /** Faz o relogio vibrar/tocar pra achar ele. */
    public synchronized boolean findWatch() {
        if (writeChar == null || gatt == null) return false;
        rawQueue.add(Packet.findWatch());
        pump();
        return true;
    }
}
