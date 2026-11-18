package io.github.gembirdt_bit.atmosformat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Briše disk i pravi jednu particiju (MBR ili GPT) sa FAT32 ili exFAT fajl sistemom.
 * Pun format pre toga upisuje nule po celom disku i proverava ih čitanjem.
 */
final class Formatter {

    static final int FS_EXFAT = 0, FS_FAT32 = 1;

    interface Progress { void on(int pct, String msg); }

    /* veći blokovi = manje komandi preko USB-a = brže (do Androida 9 USB prenos ide u manjim delovima) */
    static final int BLOCK = android.os.Build.VERSION.SDK_INT >= 28 ? 4 * 1024 * 1024 : 1024 * 1024;

    volatile boolean cancel;
    String info = "";
    long badBlocks;
    final Random rnd = new Random();

    void run(Disk d, int fs, boolean gpt, boolean full, String label, Progress p) throws IOException {
        final int bps = d.blockSize;
        if (bps < 512 || bps > 4096 || (bps & (bps - 1)) != 0) throw new IOException("Nepodržana veličina sektora: " + bps);
        final long disk = d.sectors;
        final long align = (1024L * 1024) / bps;
        if (disk < align * 8) throw new IOException("Disk je premali");

        // ---------- raspored particije ----------
        long start = align, size;
        int entrySectors = 16384 / bps;          // 128 unosa × 128 B
        boolean capped = false;
        if (gpt) {
            long lastUsable = disk - 1 - entrySectors - 1;
            size = ((lastUsable + 1 - start) / align) * align;
        } else {
            long end = Math.min(disk, 0xFFFFFFFFL);
            capped = disk > 0xFFFFFFFFL;
            size = ((end - start) / align) * align;
        }

        // ---------- pun format: nule + provera ----------
        int base = 0;
        if (full) {
            badBlocks = 0;
            int per = Math.max(1, BLOCK / bps);
            byte[] z = new byte[per * bps];
            byte[] r = new byte[per * bps];
            long t0 = System.currentTimeMillis();
            for (long lba = 0; lba < disk; lba += per) {
                if (cancel) throw new IOException("Prekinuto — disk NIJE formatiran.");
                int n = (int) Math.min(per, disk - lba);
                boolean ok = d.write(lba, n, z);
                if (!ok && d.last != null && d.last.senseKey == 7) throw new IOException("Disk je zaštićen od upisa");
                if (ok) {
                    ok = d.read(lba, n, r);
                    if (ok) for (int i = 0; i < n * bps; i++) if (r[i] != 0) { ok = false; break; }
                }
                if (!ok) badBlocks++;
                if ((lba / per) % (BLOCK >= 4 * 1024 * 1024 ? 4 : 16) == 0) {
                    long done = lba + n;
                    double el = (System.currentTimeMillis() - t0) / 1000.0;
                    double mbs = el > 0 ? done * (double) bps / el / 1e6 : 0;
                    long eta = done > 0 ? (long) (el * (disk - done) / done) : 0;
                    p.on((int) (done * 85 / disk), String.format(Locale.ROOT,
                            "Upis i provera sektora: %.1f%% · %.0f MB/s · još ~%s · loših: %d",
                            done * 100.0 / disk, mbs, MainActivity.dur(eta), badBlocks));
                }
            }
            if (badBlocks > 0)
                throw new IOException("Pronađeno je " + badBlocks + " delova diska koji se ne mogu ispravno upisati/pročitati. Disk je neispravan i NIJE formatiran.");
            base = 85;
        } else {
            // obriši stare particione tabele (početak i kraj diska)
            p.on(1, "Brisanje stare particione tabele…");
            zero(d, 0, align);
            zero(d, disk - align, align);
        }
        final int from = base;

        // ---------- fajl sistem ----------
        if (cancel) throw new IOException("Prekinuto.");
        Progress sub = (pct, msg) -> p.on(from + pct * (97 - from) / 100, msg);
        int type;
        if (fs == FS_EXFAT) { exfat(d, start, size, label, sub, !full); type = 0x07; }
        else { fat32(d, start, size, label, sub, !full); type = 0x0C; }

        // ---------- particiona tabela ----------
        p.on(98, "Upis particione tabele…");
        if (gpt) writeGpt(d, start, start + size - 1, entrySectors);
        else writeMbr(d, start, size, type);
        d.syncCache();

        info += gpt ? " · GPT" : " · MBR";
        if (capped) info += " (MBR koristi najviše 2 TB — za ceo disk izaberi GPT)";
        p.on(100, "Gotovo");
    }

    // ======================================================================
    // Particione tabele
    // ======================================================================

    private void writeMbr(Disk d, long start, long size, int type) throws IOException {
        byte[] mbr = new byte[d.blockSize];
        ByteBuffer m = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN);
        int e = 446;
        mbr[e + 1] = (byte) 0xFE; mbr[e + 2] = (byte) 0xFF; mbr[e + 3] = (byte) 0xFF;
        mbr[e + 4] = (byte) type;
        mbr[e + 5] = (byte) 0xFE; mbr[e + 6] = (byte) 0xFF; mbr[e + 7] = (byte) 0xFF;
        m.putInt(e + 8, (int) start);
        m.putInt(e + 12, (int) size);
        m.putInt(440, rnd.nextInt());
        mbr[510] = 0x55; mbr[511] = (byte) 0xAA;
        wr(d, 0, mbr);
    }

    private static final byte[] BASIC_DATA = {
            (byte) 0xA2, (byte) 0xA0, (byte) 0xD0, (byte) 0xEB, (byte) 0xE5, (byte) 0xB9, 0x33, 0x44,
            (byte) 0x87, (byte) 0xC0, 0x68, (byte) 0xB6, (byte) 0xB7, 0x26, (byte) 0x99, (byte) 0xC7};

    private byte[] guid() {
        byte[] g = new byte[16];
        rnd.nextBytes(g);
        g[7] = (byte) ((g[7] & 0x0F) | 0x40);   // verzija 4 (polje 3 je little-endian)
        g[8] = (byte) ((g[8] & 0x3F) | 0x80);
        return g;
    }

    private void writeGpt(Disk d, long first, long last, int entrySectors) throws IOException {
        int bps = d.blockSize;
        long disk = d.sectors;
        long lastLba = disk - 1;

        // zaštitni MBR
        byte[] mbr = new byte[bps];
        ByteBuffer m = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN);
        int e = 446;
        mbr[e + 2] = 0x02;
        mbr[e + 4] = (byte) 0xEE;
        mbr[e + 5] = (byte) 0xFF; mbr[e + 6] = (byte) 0xFF; mbr[e + 7] = (byte) 0xFF;
        m.putInt(e + 8, 1);
        m.putInt(e + 12, (int) Math.min(disk - 1, 0xFFFFFFFFL));
        mbr[510] = 0x55; mbr[511] = (byte) 0xAA;

        // tabela particija
        byte[] entries = new byte[entrySectors * bps];
        ByteBuffer en = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy(BASIC_DATA, 0, entries, 0, 16);
        System.arraycopy(guid(), 0, entries, 16, 16);
        en.putLong(32, first);
        en.putLong(40, last);
        en.putLong(48, 0);
        byte[] name = "Basic data partition".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(name, 0, entries, 56, Math.min(name.length, 72));
        CRC32 c = new CRC32();
        c.update(entries, 0, 128 * 128);
        int entriesCrc = (int) c.getValue();

        byte[] diskGuid = guid();
        byte[] primary = gptHeader(bps, 1, lastLba, 2 + entrySectors, lastLba - 1 - entrySectors, diskGuid, 2, entriesCrc);
        byte[] backup = gptHeader(bps, lastLba, 1, 2 + entrySectors, lastLba - 1 - entrySectors, diskGuid, lastLba - entrySectors, entriesCrc);

        if (!d.write(lastLba - entrySectors, entrySectors, entries)) throw writeErr(d, lastLba - entrySectors);
        wr(d, lastLba, backup);
        if (!d.write(2, entrySectors, entries)) throw writeErr(d, 2);
        wr(d, 1, primary);
        wr(d, 0, mbr);
    }

    private static byte[] gptHeader(int bps, long my, long alt, long firstUsable, long lastUsable, byte[] diskGuid, long entriesLba, int entriesCrc) {
        byte[] h = new byte[bps];
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        b.putInt(8, 0x00010000);
        b.putInt(12, 92);
        b.putInt(16, 0);
        b.putLong(24, my);
        b.putLong(32, alt);
        b.putLong(40, firstUsable);
        b.putLong(48, lastUsable);
        System.arraycopy(diskGuid, 0, h, 56, 16);
        b.putLong(72, entriesLba);
        b.putInt(80, 128);
        b.putInt(84, 128);
        b.putInt(88, entriesCrc);
        CRC32 c = new CRC32();
        c.update(h, 0, 92);
        b.putInt(16, (int) c.getValue());
        return h;
    }

    // ======================================================================
    // FAT32
    // ======================================================================

    private void fat32(Disk d, long start, long total, String label, Progress p, boolean zeroAll) throws IOException {
        final int bps = d.blockSize;
        if (total > 0xFFFFFFFFL) total = 0xFFFFFFFFL;   // FAT32 ograničenje broja sektora
        long bytes = total * bps;
        int clusterBytes;
        if (bytes <= 8L << 30) clusterBytes = 4096;
        else if (bytes <= 16L << 30) clusterBytes = 8192;
        else if (bytes <= 32L << 30) clusterBytes = 16384;
        else clusterBytes = 32768;
        if (clusterBytes < bps) clusterBytes = bps;

        int spc, reserved, fatSz;
        long clusters;
        while (true) {
            spc = clusterBytes / bps;
            reserved = 32;
            fatSz = fatSize(total, reserved, spc, bps);
            int pad = (int) ((spc - (reserved + 2L * fatSz) % spc) % spc);
            reserved += pad;
            fatSz = fatSize(total, reserved, spc, bps);
            clusters = (total - reserved - 2L * fatSz) / spc;
            if (clusters < 0x0FFFFFF5L || clusterBytes >= 65536) break;
            clusterBytes *= 2;
        }
        if (clusters < 65525) throw new IOException("Disk je premali za FAT32 — izaberi exFAT");
        if (clusters >= 0x0FFFFFF5L) throw new IOException("Disk je prevelik za FAT32 — izaberi exFAT");
        info = String.format(Locale.ROOT, "FAT32, klaster %d KB, %s", clusterBytes / 1024, MainActivity.size(total * bps));

        long fatStart = start + reserved;
        long dataStart = fatStart + 2L * fatSz;
        if (zeroAll) {
            long len = (dataStart + spc) - start;
            zero(d, start, len, done -> p.on((int) (done * 90 / len), "Brisanje FAT tabela…"));
        }

        p.on(92, "Upis boot sektora…");
        int volId = rnd.nextInt();
        byte[] boot = new byte[bps];
        ByteBuffer b = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN);
        boot[0] = (byte) 0xEB; boot[1] = 0x58; boot[2] = (byte) 0x90;
        putName(boot, 3, "MSWIN4.1", 8);
        b.putShort(11, (short) bps);
        boot[13] = (byte) spc;
        b.putShort(14, (short) reserved);
        boot[16] = 2;
        boot[21] = (byte) 0xF8;
        b.putShort(24, (short) 63);
        b.putShort(26, (short) 255);
        b.putInt(28, (int) start);
        b.putInt(32, (int) Math.min(total, 0xFFFFFFFFL));
        b.putInt(36, fatSz);
        b.putInt(44, 2);
        b.putShort(48, (short) 1);
        b.putShort(50, (short) 6);
        boot[64] = (byte) 0x80;
        boot[66] = 0x29;
        b.putInt(67, volId);
        putName(boot, 71, label, 11);
        putName(boot, 82, "FAT32", 8);
        boot[90] = (byte) 0xF4; boot[91] = (byte) 0xEB; boot[92] = (byte) 0xFD;
        boot[510] = 0x55; boot[511] = (byte) 0xAA;

        byte[] fsi = new byte[bps];
        ByteBuffer f = ByteBuffer.wrap(fsi).order(ByteOrder.LITTLE_ENDIAN);
        f.putInt(0, 0x41615252);
        f.putInt(484, 0x61417272);
        f.putInt(488, (int) (clusters - 1));
        f.putInt(492, 3);
        f.putInt(508, 0xAA550000);
        byte[] third = new byte[bps];
        third[510] = 0x55; third[511] = (byte) 0xAA;
        for (int s : new int[]{0, 6}) {
            wr(d, start + s, boot);
            wr(d, start + s + 1, fsi);
            wr(d, start + s + 2, third);
        }

        byte[] fat0 = new byte[bps];
        ByteBuffer fb = ByteBuffer.wrap(fat0).order(ByteOrder.LITTLE_ENDIAN);
        fb.putInt(0, 0x0FFFFFF8);
        fb.putInt(4, 0x0FFFFFFF);
        fb.putInt(8, 0x0FFFFFFF);
        wr(d, fatStart, fat0);
        wr(d, fatStart + fatSz, fat0);

        byte[] root = new byte[bps];
        putName(root, 0, label, 11);
        root[11] = 0x08;
        wr(d, dataStart, root);
    }

    private static int fatSize(long total, int reserved, int spc, int bps) {
        long perSector = bps / 4;
        long fat = 1;
        for (int i = 0; i < 50; i++) {
            long cl = (total - reserved - 2 * fat) / spc;
            long need = (cl + 2 + perSector - 1) / perSector;
            if (need <= fat) break;
            fat = need;
        }
        return (int) fat;
    }

    // ======================================================================
    // exFAT
    // ======================================================================

    private void exfat(Disk d, long start, long total, String label, Progress p, boolean zeroAll) throws IOException {
        final int bps = d.blockSize;
        final int bpsShift = Integer.numberOfTrailingZeros(bps);
        long bytes = total * bps;
        int clusterBytes;
        if (bytes <= 256L << 20) clusterBytes = 4096;
        else if (bytes <= 32L << 30) clusterBytes = 32768;
        else clusterBytes = 131072;
        if (clusterBytes < bps) clusterBytes = bps;
        int spc = clusterBytes / bps;
        int spcShift = Integer.numberOfTrailingZeros(spc);

        long fatOffset = Math.max(24, (1024L * 1024) / bps);
        long estClusters = (total - fatOffset) / spc;
        long fatLength = ((estClusters + 2) * 4 + bps - 1) / bps;
        long heapOffset = ((fatOffset + fatLength + spc - 1) / spc) * spc;
        long clusterCount = (total - heapOffset) / spc;
        if (clusterCount > 0xFFFFFFF5L) throw new IOException("Disk je prevelik");
        if (clusterCount < 16) throw new IOException("Disk je premali");

        long bitmapBytes = (clusterCount + 7) / 8;
        long bitmapClusters = (bitmapBytes + clusterBytes - 1) / clusterBytes;
        byte[] upcase = upcaseTable();
        long upcaseClusters = (upcase.length + clusterBytes - 1) / clusterBytes;
        long bitmapCl = 2, upcaseCl = bitmapCl + bitmapClusters, rootCl = upcaseCl + upcaseClusters;
        long used = bitmapClusters + upcaseClusters + 1;
        info = String.format(Locale.ROOT, "exFAT, klaster %d KB, %s", clusterBytes / 1024, MainActivity.size(total * bps));

        // nule: FAT + prvi klasteri (bitmapa, up-case, koren)
        if (zeroAll) {
            long fatEnd = fatOffset + fatLength;
            long len1 = fatLength;
            long len2 = used * spc;
            final long all = len1 + len2;
            zero(d, start + fatOffset, len1, done -> p.on((int) (done * 85 / all), "Brisanje FAT tabele…"));
            zero(d, start + heapOffset, len2, done -> p.on((int) ((len1 + done) * 85 / all), "Priprema…"));
            zero(d, start, Math.min(24, fatOffset), null);
            if (fatEnd < heapOffset) zero(d, start + fatEnd, heapOffset - fatEnd, null);
        }
        p.on(88, "Upis FAT tabele…");

        // FAT lanci
        long entries = 2 + used;
        int fatBytes = (int) (((entries * 4) + bps - 1) / bps * bps);
        byte[] fat = new byte[fatBytes];
        ByteBuffer fb = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN);
        fb.putInt(0, 0xFFFFFFF8);
        fb.putInt(4, 0xFFFFFFFF);
        chain(fb, bitmapCl, bitmapClusters);
        chain(fb, upcaseCl, upcaseClusters);
        chain(fb, rootCl, 1);
        if (!d.write(start + fatOffset, fatBytes / bps, fat)) throw writeErr(d, start + fatOffset);

        // bitmapa zauzetih klastera
        int bmBytes = (int) Math.min(bitmapBytes, ((used + 7) / 8 + bps - 1) / bps * bps);
        bmBytes = (bmBytes + bps - 1) / bps * bps;
        byte[] bm = new byte[bmBytes];
        for (long i = 0; i < used; i++) bm[(int) (i >> 3)] |= (byte) (1 << (i & 7));
        long bmLba = start + heapOffset + (bitmapCl - 2) * spc;
        if (!d.write(bmLba, bmBytes / bps, bm)) throw writeErr(d, bmLba);

        // up-case tabela
        int ucBytes = (int) (upcaseClusters * clusterBytes);
        byte[] uc = new byte[ucBytes];
        System.arraycopy(upcase, 0, uc, 0, upcase.length);
        long ucLba = start + heapOffset + (upcaseCl - 2) * spc;
        if (!d.write(ucLba, ucBytes / bps, uc)) throw writeErr(d, ucLba);
        int ucSum = 0;
        for (byte x : upcase) ucSum = ((ucSum & 1) != 0 ? 0x80000000 : 0) + (ucSum >>> 1) + (x & 0xFF);

        // korenski direktorijum
        byte[] root = new byte[clusterBytes];
        ByteBuffer rb = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN);
        String lab = label.length() > 11 ? label.substring(0, 11) : label;
        if (lab.isEmpty()) {
            root[0] = 0x03;
        } else {
            root[0] = (byte) 0x83;
            root[1] = (byte) lab.length();
            byte[] lb = lab.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(lb, 0, root, 2, Math.min(lb.length, 22));
        }
        root[32] = (byte) 0x81;
        rb.putInt(32 + 20, (int) bitmapCl);
        rb.putLong(32 + 24, bitmapBytes);
        root[64] = (byte) 0x82;
        rb.putInt(64 + 4, ucSum);
        rb.putInt(64 + 20, (int) upcaseCl);
        rb.putLong(64 + 24, upcase.length);
        long rootLba = start + heapOffset + (rootCl - 2) * spc;
        if (!d.write(rootLba, spc, root)) throw writeErr(d, rootLba);

        // boot region (glavni + rezervni)
        p.on(95, "Upis boot sektora…");
        byte[] region = new byte[12 * bps];
        ByteBuffer b = ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN);
        region[0] = (byte) 0xEB; region[1] = 0x76; region[2] = (byte) 0x90;
        System.arraycopy("EXFAT   ".getBytes(StandardCharsets.US_ASCII), 0, region, 3, 8);
        b.putLong(64, start);
        b.putLong(72, total);
        b.putInt(80, (int) fatOffset);
        b.putInt(84, (int) fatLength);
        b.putInt(88, (int) heapOffset);
        b.putInt(92, (int) clusterCount);
        b.putInt(96, (int) rootCl);
        b.putInt(100, rnd.nextInt());
        b.putShort(104, (short) 0x0100);
        b.putShort(106, (short) 0);
        region[108] = (byte) bpsShift;
        region[109] = (byte) spcShift;
        region[110] = 1;
        region[111] = (byte) 0x80;
        region[112] = 0;
        region[120] = (byte) 0xF4; region[121] = (byte) 0xEB; region[122] = (byte) 0xFD;
        region[510] = 0x55; region[511] = (byte) 0xAA;
        for (int s = 1; s <= 8; s++) {
            int o = s * bps + bps - 4;
            region[o + 2] = 0x55; region[o + 3] = (byte) 0xAA;
        }
        int sum = 0;
        for (int i = 0; i < 11 * bps; i++) {
            if (i == 106 || i == 107 || i == 112) continue;
            sum = ((sum & 1) != 0 ? 0x80000000 : 0) + (sum >>> 1) + (region[i] & 0xFF);
        }
        for (int i = 11 * bps; i < 12 * bps; i += 4) b.putInt(i, sum);
        if (!d.write(start + 12, 12, region)) throw writeErr(d, start + 12);
        if (!d.write(start, 12, region)) throw writeErr(d, start);
    }

    private static void chain(ByteBuffer fat, long first, long count) {
        for (long i = 0; i < count; i++) {
            long c = first + i;
            fat.putInt((int) (c * 4), i == count - 1 ? 0xFFFFFFFF : (int) (c + 1));
        }
    }

    /** Up-case tabela (kompresovana): svaki znak → veliko slovo. */
    static byte[] upcaseTable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i <= 0xFFFF) {
            char c = (char) i;
            char u = Character.toUpperCase(c);
            if (u == c) {
                int j = i;
                while (j <= 0xFFFF && Character.toUpperCase((char) j) == (char) j) j++;
                int run = j - i;
                if (run >= 2 || i == 0xFFFF) {
                    le16(out, 0xFFFF);
                    le16(out, run);
                } else {
                    le16(out, u);
                }
                i = j;
            } else {
                le16(out, u);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    // ======================================================================
    // Pomoćne
    // ======================================================================

    interface Done { void on(long done); }

    private void zero(Disk d, long lba, long count) throws IOException { zero(d, lba, count, null); }

    private void zero(Disk d, long lba, long count, Done cb) throws IOException {
        int per = Math.max(1, BLOCK / d.blockSize);
        byte[] z = new byte[per * d.blockSize];
        long done = 0;
        while (done < count) {
            if (cancel) throw new IOException("Prekinuto.");
            int n = (int) Math.min(per, count - done);
            if (!d.write(lba + done, n, z)) throw writeErr(d, lba + done);
            done += n;
            if (cb != null) cb.on(done);
        }
    }

    private static void wr(Disk d, long lba, byte[] sector) throws IOException {
        if (!d.write(lba, 1, sector)) throw writeErr(d, lba);
    }

    static IOException writeErr(Disk d, long lba) {
        UsbBot.Result r = d.last;
        if (r != null && r.senseKey == 7) return new IOException("Disk je zaštićen od upisa");
        return new IOException("Greška pri upisu na sektoru " + lba);
    }

    private static void putName(byte[] b, int off, String s, int len) {
        String t = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < len; i++) {
            char c = i < t.length() ? t.charAt(i) : ' ';
            if (c < 0x20 || c > 0x7E || "\"*+,./:;<=>?[\\]|".indexOf(c) >= 0) c = '_';
            b[off + i] = (byte) c;
        }
    }
}
