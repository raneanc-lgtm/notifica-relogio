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
    }

    public synchronized boolean isReady() { return writeChar != null; }

    /** Coloca uma notificacao na fila de envio. */
    public synchronized void sendNotification(int app, String title, String body) {
        int times = Prefs.vibes(ctx);
        for (int i = 1; i < times; i++) {
            // repete o aviso pra vibrar mais vezes (o relogio nao deixa vibrar mais forte)
            main.postDelayed(() -> enqueue(app, title, body), 700L * i);
        }
        enqueue(app, title, body);
    }

    private synchronized void enqueue(int app, String title, String body) {
        queue.add(new String[]{String.valueOf(app), title, body});
        while (queue.size() > 10) queue.poll();
        ensureConnected();
        pump();
    }

    private synchronized void pump() {
        if (writing || writeChar == null || gatt == null || queue.isEmpty()) return;
        String[] n = queue.poll();
        byte[] pkt = Packet.notification(Integer.parseInt(n[0]), n[1], n[2], mtu - 3 - 5);
        lastPkt = n;
        writeChar.setWriteType(noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writeChar.setValue(pkt);
        writing = gatt.writeCharacteristic(writeChar);
        Log.i(TAG, "envio " + Packet.hex(pkt) + " ok=" + writing);
        if (!writing) main.postDelayed(this::pump, 500);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Conectado, preparando…");
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized (WatchLink.this) { writeChar = null; writing = false; mtu = 23; }
                setStatus("Relógio fora de alcance (reconecta sozinho)");
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
            if (st != BluetoothGatt.GATT_SUCCESS) setStatus("Falha no envio (código " + st + ")");
            else setStatus("Enviado pro relógio ✔" + (gotReply ? "" : " (relógio ainda não respondeu)"));
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
        setStatus("Conectado ✔");
        pump();
    }
}
