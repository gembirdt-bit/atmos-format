package io.github.gembirdt_bit.atmosformat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String ACTION_PERM = "io.github.gembirdt_bit.atmosformat.USB_PERMISSION";

    static final int BG = 0xFF0C1512, CARD = 0xFF16251F, CARD2 = 0xFF1D3029, TXT = 0xFFEEF7F3,
            MUTED = 0xFF98B3A8, ACC = 0xFF0F9D78, OK = 0xFF34D399, WARN = 0xFFFBBF24, BAD = 0xFFF87171;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UsbManager usb;
    private TextView status, diskName, diskInfo, progText, fsNote;
    private Button scanBtn, formatBtn, cancelBtn;
    private LinearLayout diskCard, optCard, progCard;
    private ProgressBar prog;
    private EditText label;
    private RadioGroup fsGroup, styleGroup, modeGroup;

    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface itf;
    private Disk disk;
    private String model = "";

    private volatile boolean busy;
    private Formatter running;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PERM.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) open(d);
                else setStatus("Pristup disku nije odobren. Pritisni „Pronađi disk” i dozvoli pristup.", WARN);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && d != null && d.getDeviceId() == device.getDeviceId()) {
                    Formatter f = running;
                    if (f != null) f.cancel = true;
                    worker.execute(MainActivity.this::closeDisk);
                    ui.post(() -> { showDisk(false); setStatus("Disk je isključen.", MUTED); });
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (!busy) ui.postDelayed(MainActivity.this::scan, 800);
            }
        }
    };

    // ======================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        sv.addView(root);

        root.addView(text("🧹 Atmos Format", 24, TXT, true));
        TextView sub = text("Brisanje i formatiranje USB diskova preko OTG kabla", 14, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(14));
        root.addView(sub);

        LinearLayout sc = card();
        status = text("Priključi disk preko OTG kabla, pa pritisni „Pronađi disk”.", 15, TXT, false);
        sc.addView(status);
        scanBtn = button("🔍 Pronađi disk", true);
        scanBtn.setOnClickListener(v -> scan());
        sc.addView(scanBtn, lp(12));
        root.addView(sc);

        // --- disk ---
        diskCard = card();
        diskName = text("", 17, TXT, true);
        diskCard.addView(diskName);
        diskInfo = text("", 14, MUTED, false);
        diskInfo.setPadding(0, dp(4), 0, 0);
        diskCard.addView(diskInfo);
        root.addView(diskCard);

        // --- podešavanja ---
        optCard = card();
        optCard.addView(head("Podešavanja"));
        optCard.addView(text("Naziv diska", 13, MUTED, false));
        label = new EditText(this);
        label.setText("DISK");
        label.setTextColor(TXT);
        label.setSingleLine(true);
        label.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        label.setBackgroundTintList(ColorStateList.valueOf(ACC));
        optCard.addView(label);

        optCard.addView(sectionLbl("Fajl sistem"));
        fsGroup = group(new String[]{"exFAT — preporučeno (TV, auto, telefon, Windows, Mac)", "FAT32 — stari uređaji (fajl najviše 4 GB)"});
        optCard.addView(fsGroup);
        fsNote = text("NTFS formatiranje postoji samo u Windows verziji Atmos Format-a.", 12, MUTED, false);
        optCard.addView(fsNote);

        optCard.addView(sectionLbl("Particiona tabela"));
        styleGroup = group(new String[]{"Automatski", "MBR — najbolja kompatibilnost", "GPT — za diskove veće od 2 TB"});
        optCard.addView(styleGroup);

        optCard.addView(sectionLbl("Način"));
        modeGroup = group(new String[]{"Brzi format", "Pun format — upisuje i proverava svaki sektor (traje satima)"});
        optCard.addView(modeGroup);

        formatBtn = button("🧹 Obriši i formatiraj", true);
        GradientDrawable red = new GradientDrawable();
        red.setColor(0xFFB91C1C);
        red.setCornerRadius(dp(12));
        formatBtn.setBackground(red);
        formatBtn.setOnClickListener(v -> confirm());
        optCard.addView(formatBtn, lp(16));
        root.addView(optCard);

        // --- napredak ---
        progCard = card();
        progCard.addView(head("Formatiranje"));
        prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(100);
        prog.setProgressTintList(ColorStateList.valueOf(ACC));
        prog.setProgressBackgroundTintList(ColorStateList.valueOf(CARD2));
        progCard.addView(prog, new LinearLayout.LayoutParams(-1, dp(10)));
        progText = text("", 14, TXT, false);
        progText.setPadding(0, dp(10), 0, 0);
        progCard.addView(progText);
        cancelBtn = button("Prekini", false);
        cancelBtn.setOnClickListener(v -> {
            Formatter f = running;
            if (f != null) f.cancel = true;
            cancelBtn.setEnabled(false);
        });
        progCard.addView(cancelBtn, lp(10));
        root.addView(progCard);

        // --- podnožje ---
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setPadding(0, dp(18), 0, 0);
        Button coffee = button("☕ Doniraj autoru", false);
        coffee.setTextSize(13);
        coffee.setOnClickListener(v -> donate());
        foot.addView(coffee, new LinearLayout.LayoutParams(-2, -2));
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView vt = text("Atmos Format " + ver + " · Željko Ilić · Gemb-Bit", 12, MUTED, false);
        vt.setGravity(Gravity.CENTER);
        vt.setPadding(0, dp(8), 0, 0);
        foot.addView(vt);
        root.addView(foot);

        setContentView(sv);
        showDisk(false);
        progCard.setVisibility(View.GONE);

        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) ui.postDelayed(this::scan, 500);
    }

    @Override
    public void onBackPressed() {
        if (busy) { toast("Formatiranje je u toku — sačekaj da se završi."); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        Formatter f = running;
        if (f != null) f.cancel = true;
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        worker.execute(this::closeDisk);
        worker.shutdown();
        super.onDestroy();
    }

    // ======================================================================
    // USB
    // ======================================================================

    private static UsbInterface massStorage(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE && f.getInterfaceProtocol() == 0x50) return f;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) return f;
        }
        return null;
    }

    private void scan() {
        if (busy) return;
        UsbDevice found = null;
        for (UsbDevice d : usb.getDeviceList().values()) if (massStorage(d) != null) { found = d; break; }
        if (found == null) {
            worker.execute(this::closeDisk);
            showDisk(false);
            setStatus("Nije pronađen USB disk.\n\n• Proveri da telefon podržava OTG.\n• 2.5\" diskovima nekad treba dodatno napajanje (Y kabl ili hub sa napajanjem).", WARN);
            return;
        }
        if (!usb.hasPermission(found)) {
            setStatus("Dozvoli aplikaciji pristup disku…", MUTED);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            usb.requestPermission(found, PendingIntent.getBroadcast(this, 0, new Intent(ACTION_PERM).setPackage(getPackageName()), fl));
            return;
        }
        open(found);
    }

    private void open(UsbDevice d) {
        if (busy) return;
        busy = true;
        scanBtn.setEnabled(false);
        showDisk(false);
        setStatus("Čitam podatke o disku…", MUTED);
        worker.execute(() -> {
            String err = null;
            try {
                closeDisk();
                UsbInterface f = massStorage(d);
                UsbDeviceConnection c = usb.openDevice(d);
                if (c == null || f == null) throw new IOException("Ne mogu da otvorim USB uređaj");
                if (!c.claimInterface(f, true)) { c.close(); throw new IOException("Ne mogu da preuzmem disk od sistema"); }
                device = d; conn = c; itf = f;
                Disk k = new Disk(new UsbBot(c, f), d.getVendorId(), d.getProductId());
                k.init();
                if (k.sectors <= 0) throw new IOException("Disk ne prijavljuje kapacitet (možda nema napajanja)");
                String m = "";
                try {
                    if (k.probeAta()) m = Disk.ataString(k.ataIdentify, 27, 20);
                    else if (k.probeNvme()) m = Disk.ascii(k.nvmeIdentify, 24, 40);
                } catch (IOException ignored) { }
                model = m;
                disk = k;
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String fe = err;
            ui.post(() -> {
                busy = false;
                scanBtn.setEnabled(true);
                if (fe != null || disk == null) {
                    setStatus("Greška: " + fe + "\n\nIzvuci i ponovo uključi disk, pa probaj opet.", BAD);
                    return;
                }
                Disk k = disk;
                String name = !model.isEmpty() ? model : (k.vendor + " " + k.product).trim();
                if (name.isEmpty()) name = "USB disk";
                diskName.setText(name);
                diskInfo.setText(size(k.capacityBytes()) + " · sektor " + k.blockSize + " B · USB "
                        + String.format(Locale.ROOT, "%04X:%04X", k.vid, k.pid)
                        + (k.bridge.isEmpty() ? "" : " (" + k.bridge + ")"));
                ((RadioButton) styleGroup.getChildAt(0)).setText(k.capacityBytes() > 2199023255552L ? "Automatski (GPT, disk je veći od 2 TB)" : "Automatski (MBR)");
                showDisk(true);
                setStatus("Disk je spreman. Izaberi podešavanja i pritisni „Obriši i formatiraj”.", TXT);
            });
        });
    }

    private void closeDisk() {
        try {
            if (conn != null) {
                if (itf != null) conn.releaseInterface(itf);
                conn.close();
            }
        } catch (Exception ignored) { }
        conn = null; itf = null; disk = null; device = null;
    }

    private void showDisk(boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        diskCard.setVisibility(v);
        optCard.setVisibility(v);
        if (!show && !busy && progCard != null) progCard.setVisibility(View.GONE);
    }

    // ======================================================================
    // Formatiranje
    // ======================================================================

    private void confirm() {
        if (busy || disk == null) return;
        final Disk k = disk;
        final int fs = checked(fsGroup) == 0 ? Formatter.FS_EXFAT : Formatter.FS_FAT32;
        int st = checked(styleGroup);
        final boolean gpt = st == 2 || (st == 0 && k.capacityBytes() > 2199023255552L);
        final boolean full = checked(modeGroup) == 1;
        String lab = label.getText().toString().trim();
        if (lab.isEmpty()) lab = "DISK";
        final String fl = lab;

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        l.addView(text("SVI PODACI NA DISKU BIĆE OBRISANI.\n\n" + diskName.getText() + " (" + size(k.capacityBytes()) + ")\n"
                + "Novi format: " + (fs == Formatter.FS_EXFAT ? "exFAT" : "FAT32") + ", " + (gpt ? "GPT" : "MBR") + ", "
                + (full ? "pun format" : "brzi format"), 14, TXT, false));
        CheckBox cb = new CheckBox(this);
        cb.setText("Razumem da će svi podaci biti obrisani");
        cb.setTextColor(TXT);
        cb.setButtonTintList(ColorStateList.valueOf(ACC));
        l.addView(cb, lp(12));
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("⚠ Obrisati disk?")
                .setView(l)
                .setNegativeButton("Odustani", null)
                .setPositiveButton("Obriši i formatiraj", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!cb.isChecked()) { toast("Potvrdi da razumeš da se podaci brišu."); return; }
            d.dismiss();
            format(k, fs, gpt, full, fl);
        }));
        d.show();
    }

    private void format(Disk k, int fs, boolean gpt, boolean full, String lab) {
        busy = true;
        scanBtn.setEnabled(false);
        formatBtn.setEnabled(false);
        progCard.setVisibility(View.VISIBLE);
        cancelBtn.setVisibility(View.VISIBLE);
        cancelBtn.setEnabled(true);
        prog.setProgress(0);
        progText.setTextColor(TXT);
        progText.setText("Počinje…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Formatter f = new Formatter();
        running = f;
        worker.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            String err = null;
            try {
                f.run(k, fs, gpt, full, lab, (pct, msg) -> ui.post(() -> { prog.setProgress(pct); progText.setText(msg); }));
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String fe = err;
            final long secs = (SystemClock.elapsedRealtime() - t0) / 1000;
            ui.post(() -> {
                busy = false;
                running = null;
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                scanBtn.setEnabled(true);
                formatBtn.setEnabled(true);
                cancelBtn.setVisibility(View.GONE);
                if (fe == null) {
                    prog.setProgress(100);
                    progText.setTextColor(OK);
                    progText.setText("✔ Disk je formatiran za " + dur(secs) + ".\n" + f.info + "\n\nIzvuci i ponovo uključi disk da bi ga telefon video.");
                } else {
                    progText.setTextColor(BAD);
                    progText.setText("❗ " + fe + "\n\nAko je formatiranje prekinuto, disk ostaje neupotrebljiv dok se ponovo ne formatira.");
                }
            });
        });
    }

    // ======================================================================
    // Doniraj autoru
    // ======================================================================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void donate() {
        Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        WebView wv = new WebView(this);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void copy(String t) {
                ui.post(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Račun", t));
                });
            }

            @JavascriptInterface
            public void close() { ui.post(d::dismiss); }
        }, "Atmos");
        wv.loadUrl("file:///android_asset/donate.html");
        d.setContentView(wv, new ViewGroup.LayoutParams(-1, -1));
        d.show();
    }

    // ======================================================================
    // Pomoćne
    // ======================================================================

    private int checked(RadioGroup g) {
        int id = g.getCheckedRadioButtonId();
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getId() == id) return i;
        return 0;
    }

    private RadioGroup group(String[] items) {
        RadioGroup g = new RadioGroup(this);
        for (String s : items) {
            RadioButton r = new RadioButton(this);
            r.setId(View.generateViewId());
            r.setText(s);
            r.setTextColor(TXT);
            r.setTextSize(14);
            r.setButtonTintList(ColorStateList.valueOf(ACC));
            r.setPadding(dp(4), dp(6), 0, dp(6));
            g.addView(r);
        }
        ((RadioButton) g.getChildAt(0)).setChecked(true);
        return g;
    }

    private TextView sectionLbl(String s) {
        TextView t = text(s, 13, MUTED, false);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private void setStatus(String s, int color) {
        ui.post(() -> { status.setText(s); status.setTextColor(color == MUTED ? TXT : color); });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = dp(topDp);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView head(String s) {
        TextView t = text(s, 16, TXT, true);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(1, 0x1AFFFFFF);
        c.setBackground(g);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = dp(12);
        c.setLayoutParams(l);
        return c;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : TXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACC : CARD2);
        g.setCornerRadius(dp(12));
        if (!primary) g.setStroke(1, 0x33FFFFFF);
        b.setBackground(g);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setStateListAnimator(null);
        return b;
    }

    static String size(long bytes) {
        if (bytes <= 0) return "0 B";
        double b = bytes;
        if (b >= 1e12) return String.format(Locale.ROOT, "%.2f TB", b / 1e12);
        if (b >= 1e9) return String.format(Locale.ROOT, "%.1f GB", b / 1e9);
        if (b >= 1e6) return String.format(Locale.ROOT, "%.0f MB", b / 1e6);
        return String.format(Locale.ROOT, "%.0f KB", b / 1e3);
    }

    static String dur(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }
}
