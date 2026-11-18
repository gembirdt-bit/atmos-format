using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace AtmosFormat
{
    sealed class MainForm : Form
    {
        internal static readonly Color BG = Color.FromArgb(0x0C, 0x15, 0x12);
        internal static readonly Color CARD = Color.FromArgb(0x16, 0x25, 0x1F);
        internal static readonly Color CARD2 = Color.FromArgb(0x1D, 0x30, 0x29);
        internal static readonly Color TXT = Color.FromArgb(0xEE, 0xF7, 0xF3);
        internal static readonly Color MUTED = Color.FromArgb(0x98, 0xB3, 0xA8);
        internal static readonly Color ACC = Color.FromArgb(0x0F, 0x9D, 0x78);
        internal static readonly Color OKC = Color.FromArgb(0x34, 0xD3, 0x99);
        internal static readonly Color WARN = Color.FromArgb(0xFB, 0xBF, 0x24);
        internal static readonly Color BAD = Color.FromArgb(0xF8, 0x71, 0x71);

        readonly ListView list = new ListView();
        readonly CheckBox showAll = new CheckBox();
        readonly TextBox label = new TextBox();
        readonly ComboBox fs = new ComboBox();
        readonly ComboBox style = new ComboBox();
        readonly RadioButton quick = new RadioButton();
        readonly RadioButton full = new RadioButton();
        readonly Button formatBtn = new Button();
        readonly Button refreshBtn = new Button();
        readonly ProgressBar progress = new ProgressBar();
        readonly Label statusLbl = new Label();
        readonly TextBox log = new TextBox();
        readonly Label info = new Label();

        List<DiskInfo> disks = new List<DiskInfo>();
        bool busy;

        public MainForm()
        {
            Text = "Atmos Format";
            try { Icon = new Icon(Assembly.GetExecutingAssembly().GetManifestResourceStream("icon.ico")); } catch { }
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);
            Font = new Font("Segoe UI", 9.75F);
            BackColor = BG;
            ForeColor = TXT;
            ClientSize = new Size(860, 680);
            MinimumSize = new Size(760, 600);
            StartPosition = FormStartPosition.CenterScreen;

            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 6, Padding = new Padding(16) };
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            Controls.Add(root);

            // --- naslov ---
            var head = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2 };
            head.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            head.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            var titles = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Dock = DockStyle.Fill, WrapContents = false };
            titles.Controls.Add(new Label { Text = "Atmos Format", Font = new Font("Segoe UI Semibold", 18F), AutoSize = true, ForeColor = TXT });
            titles.Controls.Add(new Label { Text = "Brisanje i formatiranje diskova — sistemski disk je uvek zaštićen", AutoSize = true, ForeColor = MUTED, Margin = new Padding(3, 0, 3, 8) });
            head.Controls.Add(titles, 0, 0);
            var hb = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Anchor = AnchorStyles.Right };
            showAll.Text = "Prikaži i interne diskove";
            showAll.AutoSize = true;
            showAll.ForeColor = MUTED;
            showAll.Margin = new Padding(3, 10, 12, 3);
            showAll.CheckedChanged += (s, e) => Fill();
            hb.Controls.Add(showAll);
            Style(refreshBtn, "⟳ Osveži", false);
            refreshBtn.Click += (s, e) => Reload();
            hb.Controls.Add(refreshBtn);
            head.Controls.Add(hb, 1, 0);
            root.Controls.Add(head, 0, 0);

            // --- lista diskova ---
            list.Dock = DockStyle.Fill;
            list.View = View.Details;
            list.FullRowSelect = true;
            list.MultiSelect = false;
            list.HideSelection = false;
            list.BackColor = CARD;
            list.ForeColor = TXT;
            list.BorderStyle = BorderStyle.None;
            list.OwnerDraw = true;
            list.Columns.Add("Disk", 55);
            list.Columns.Add("Naziv", 250);
            list.Columns.Add("Veličina", 90);
            list.Columns.Add("Veza", 70);
            list.Columns.Add("Tabela", 70);
            list.Columns.Add("Slova", 80);
            list.Columns.Add("Napomena", 200);
            list.DrawColumnHeader += (s, e) =>
            {
                using (var b = new SolidBrush(CARD2)) e.Graphics.FillRectangle(b, e.Bounds);
                TextRenderer.DrawText(e.Graphics, e.Header.Text, Font, Rectangle.Inflate(e.Bounds, -6, 0), MUTED, TextFormatFlags.VerticalCenter | TextFormatFlags.Left);
            };
            list.DrawItem += (s, e) => { };
            list.DrawSubItem += (s, e) =>
            {
                var d = e.Item.Tag as DiskInfo;
                bool sel = e.Item.Selected;
                Color back = sel ? ACC : CARD;
                using (var b = new SolidBrush(back)) e.Graphics.FillRectangle(b, e.Bounds);
                Color fc = TXT;
                if (d != null && d.Protected) fc = MUTED;
                if (e.ColumnIndex == 6 && d != null && !sel) fc = d.Protected ? BAD : d.Removable ? OKC : WARN;
                TextRenderer.DrawText(e.Graphics, e.SubItem.Text, Font, Rectangle.Inflate(e.Bounds, -6, 0), sel ? Color.White : fc,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.EndEllipsis);
            };
            list.SelectedIndexChanged += (s, e) => UpdateUi();
            var listHost = new Panel { Dock = DockStyle.Fill, Padding = new Padding(1), BackColor = CARD2, Margin = new Padding(3, 3, 3, 10) };
            listHost.Controls.Add(list);
            root.Controls.Add(listHost, 0, 1);

            // --- podešavanja ---
            var opt = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 8, BackColor = CARD, Padding = new Padding(12, 10, 12, 10) };
            for (int i = 0; i < 8; i++) opt.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            opt.Controls.Add(Lbl("Naziv:"), 0, 0);
            label.Text = "Disk";
            label.Width = 130;
            label.MaxLength = 32;
            label.BackColor = CARD2;
            label.ForeColor = TXT;
            label.BorderStyle = BorderStyle.FixedSingle;
            label.Margin = new Padding(3, 6, 16, 3);
            opt.Controls.Add(label, 1, 0);
            opt.Controls.Add(Lbl("Fajl sistem:"), 2, 0);
            Combo(fs, new[] { "NTFS", "exFAT", "FAT32" });
            fs.SelectedIndexChanged += (s, e) => UpdateUi();
            opt.Controls.Add(fs, 3, 0);
            opt.Controls.Add(Lbl("Tabela:"), 4, 0);
            Combo(style, new[] { "Automatski", "MBR", "GPT" });
            opt.Controls.Add(style, 5, 0);
            quick.Text = "Brzi format";
            quick.Checked = true;
            full.Text = "Pun format (proverava sektore, sporo)";
            foreach (var r in new[] { quick, full }) { r.AutoSize = true; r.ForeColor = TXT; r.Margin = new Padding(3, 8, 10, 3); }
            opt.Controls.Add(quick, 6, 0);
            opt.Controls.Add(full, 7, 0);
            info.AutoSize = true;
            info.ForeColor = MUTED;
            info.Margin = new Padding(3, 6, 3, 0);
            opt.Controls.Add(info, 0, 1);
            opt.SetColumnSpan(info, 8);
            root.Controls.Add(opt, 0, 2);

            // --- dugme + napredak ---
            var act = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, Margin = new Padding(3, 10, 3, 6) };
            act.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            act.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            Style(formatBtn, "🧹 Obriši i formatiraj", true);
            formatBtn.Padding = new Padding(14, 6, 14, 6);
            formatBtn.Click += (s, e) => StartFormat();
            act.Controls.Add(formatBtn, 0, 0);
            var pr = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, ColumnCount = 1 };
            progress.Dock = DockStyle.Top;
            progress.Height = 14;
            progress.Margin = new Padding(12, 6, 3, 2);
            statusLbl.AutoSize = true;
            statusLbl.ForeColor = MUTED;
            statusLbl.Margin = new Padding(12, 2, 3, 0);
            statusLbl.Text = "Izaberi disk sa liste.";
            pr.Controls.Add(progress, 0, 0);
            pr.Controls.Add(statusLbl, 0, 1);
            act.Controls.Add(pr, 1, 0);
            root.Controls.Add(act, 0, 3);

            // --- dnevnik ---
            log.Dock = DockStyle.Fill;
            log.Multiline = true;
            log.ReadOnly = true;
            log.ScrollBars = ScrollBars.Vertical;
            log.BackColor = CARD;
            log.ForeColor = MUTED;
            log.BorderStyle = BorderStyle.None;
            log.Font = new Font("Consolas", 9F);
            var logHost = new Panel { Dock = DockStyle.Fill, Padding = new Padding(8), BackColor = CARD };
            logHost.Controls.Add(log);
            root.Controls.Add(logHost, 0, 4);

            // --- podnožje ---
            var foot = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 3, Margin = new Padding(3, 10, 3, 0) };
            foot.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            foot.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            foot.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            var ver = Assembly.GetExecutingAssembly().GetName().Version;
            foot.Controls.Add(new Label { Text = "Atmos Format " + ver.ToString(3) + " · Željko Ilić · Gemb-Bit", AutoSize = true, ForeColor = MUTED, Margin = new Padding(3, 8, 3, 3) }, 0, 0);
            var coffee = new Button();
            Style(coffee, "☕ Doniraj autoru", false);
            coffee.Click += (s, e) => { using (var f = new DonateForm()) f.ShowDialog(this); };
            var apps = new Button();
            Style(apps, "📱 Sve Atmos aplikacije", false);
            apps.Click += (s, e) => { try { Process.Start(new ProcessStartInfo("https://gembirdt-bit.github.io/atmosaplikacije/") { UseShellExecute = true }); } catch { } };
            foot.Controls.Add(apps, 1, 0);
            foot.Controls.Add(coffee, 2, 0);
            root.Controls.Add(foot, 0, 5);

            Shown += (s, e) => Reload();
            FormClosing += (s, e) =>
            {
                if (busy && MessageBox.Show(this, "Formatiranje je u toku. Prekid može ostaviti disk neupotrebljiv dok se ponovo ne formatira.\n\nZatvoriti program?",
                        "Atmos Format", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) e.Cancel = true;
            };
        }

        // ------------------------------------------------------------------

        Label Lbl(string t)
        {
            return new Label { Text = t, AutoSize = true, ForeColor = MUTED, Margin = new Padding(3, 9, 4, 3) };
        }

        void Combo(ComboBox c, string[] items)
        {
            c.DropDownStyle = ComboBoxStyle.DropDownList;
            c.FlatStyle = FlatStyle.Flat;
            c.BackColor = CARD2;
            c.ForeColor = TXT;
            c.Width = 110;
            c.Margin = new Padding(3, 6, 16, 3);
            c.Items.AddRange(items);
            c.SelectedIndex = 0;
        }

        internal static void Style(Button b, string text, bool primary)
        {
            b.Text = text;
            b.AutoSize = true;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderColor = primary ? ACC : Color.FromArgb(0x33, 0x4A, 0x42);
            b.FlatAppearance.BorderSize = 1;
            b.BackColor = primary ? ACC : CARD2;
            b.ForeColor = primary ? Color.White : TXT;
            b.Padding = new Padding(10, 4, 10, 4);
            b.Cursor = Cursors.Hand;
            b.UseVisualStyleBackColor = false;
        }

        void Log(string s)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string>(Log), s); return; }
            log.AppendText(s + Environment.NewLine);
        }

        void Status(string s, Color c)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string, Color>(Status), s, c); return; }
            statusLbl.Text = s;
            statusLbl.ForeColor = c;
        }

        // ------------------------------------------------------------------

        void Reload()
        {
            if (busy) return;
            try
            {
                disks = DiskInfo.Load();
            }
            catch (Exception ex)
            {
                disks = new List<DiskInfo>();
                Log("Greška pri čitanju diskova: " + ex.Message);
            }
            Fill();
        }

        void Fill()
        {
            int keep = Selected() != null ? Selected().Number : -1;
            list.BeginUpdate();
            list.Items.Clear();
            foreach (var d in disks)
            {
                if (!showAll.Checked && !d.Removable && !d.Protected) continue;
                if (!showAll.Checked && d.Protected) continue;
                string note = d.Protected ? "Sistemski — zaštićen" : d.Removable ? "Spoljni disk" : "Interni disk — pažljivo!";
                if (d.IsOffline) note += " · offline";
                if (d.IsReadOnly) note += " · samo čitanje";
                var it = new ListViewItem(new[] { d.Number.ToString(), d.Name, DiskInfo.SizeText(d.Size), d.BusText, d.StyleText, d.LettersText, note });
                it.Tag = d;
                list.Items.Add(it);
                if (d.Number == keep) it.Selected = true;
            }
            list.EndUpdate();
            if (list.Items.Count == 0)
                Status(showAll.Checked ? "Nema diskova." : "Nema spoljnih diskova. Priključi USB disk ili uključi „Prikaži i interne diskove”.", MUTED);
            UpdateUi();
        }

        DiskInfo Selected()
        {
            return list.SelectedItems.Count == 1 ? list.SelectedItems[0].Tag as DiskInfo : null;
        }

        void UpdateUi()
        {
            var d = Selected();
            if (fs.SelectedIndex == 2 && d != null && d.Size > 34359738368UL)
                info.Text = "FAT32 u Windows-u može najviše 32 GB. Za veće diskove izaberi exFAT (za TV, auto, telefon) ili NTFS (za Windows).";
            else if (d != null && d.Size > 2199023255552UL)
                info.Text = "Disk je veći od 2 TB — „Automatski” bira GPT (MBR ne može da iskoristi ceo disk).";
            else
                info.Text = "NTFS: za Windows. exFAT: za TV, auto, telefon, Mac. FAT32: za stare uređaje (najviše 32 GB, fajl do 4 GB).";
            info.ForeColor = fs.SelectedIndex == 2 && d != null && d.Size > 34359738368UL ? WARN : MUTED;
            formatBtn.Enabled = !busy && d != null && !d.Protected;
            refreshBtn.Enabled = !busy;
            list.Enabled = !busy;
            if (!busy && d != null)
                Status(d.Protected ? "Ovaj disk je sistemski i ne može se formatirati." : "Izabran disk " + d.Number + ": " + d.Name + " (" + DiskInfo.SizeText(d.Size) + ")",
                    d.Protected ? BAD : TXT);
        }

        // ------------------------------------------------------------------

        void StartFormat()
        {
            var d = Selected();
            if (d == null || d.Protected || busy) return;
            if (fs.SelectedIndex == 2 && d.Size > 34359738368UL)
            {
                MessageBox.Show(this, "Windows ne može da formatira FAT32 veći od 32 GB.\nIzaberi exFAT ili NTFS.", "Atmos Format", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            string lab = label.Text.Trim();
            if (lab.Length == 0) lab = "Disk";
            lab = new string(lab.Where(c => c != '"' && c >= ' ').ToArray());
            int maxLab = fs.SelectedIndex == 0 ? 32 : 11;
            if (lab.Length > maxLab) lab = lab.Substring(0, maxLab);
            string fsName = fs.SelectedIndex == 0 ? "ntfs" : fs.SelectedIndex == 1 ? "exfat" : "fat32";
            bool gpt = style.SelectedIndex == 2 || (style.SelectedIndex == 0 && d.Size > 2199023255552UL);

            using (var c = new ConfirmForm(d, fs.Text, gpt ? "GPT" : "MBR", full.Checked))
                if (c.ShowDialog(this) != DialogResult.OK) return;

            // Ponovo proveri da disk nije zamenjen u međuvremenu
            DiskInfo now = null;
            try { now = DiskInfo.Load().FirstOrDefault(x => x.Number == d.Number); } catch { }
            if (now == null || now.Protected || now.Size != d.Size || now.Name != d.Name)
            {
                MessageBox.Show(this, "Disk se promenio (isključen ili zamenjen). Osveži listu i izaberi ponovo.", "Atmos Format", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                Reload();
                return;
            }

            var sb = new StringBuilder();
            sb.AppendLine("select disk " + d.Number);
            sb.AppendLine("attributes disk clear readonly noerr");
            sb.AppendLine("online disk noerr");
            sb.AppendLine("clean");
            sb.AppendLine(gpt ? "convert gpt" : "convert mbr");
            sb.AppendLine("create partition primary");
            sb.AppendLine("format fs=" + fsName + " label=\"" + lab + "\"" + (full.Checked ? "" : " quick"));
            sb.AppendLine("assign");
            sb.AppendLine("exit");

            busy = true;
            UpdateUi();
            log.Clear();
            progress.Style = ProgressBarStyle.Marquee;
            progress.MarqueeAnimationSpeed = 30;
            Status("Formatiranje je u toku — ne isključuj disk…", WARN);
            Log("Disk " + d.Number + ": " + d.Name + " (" + DiskInfo.SizeText(d.Size) + ")");
            Log("Fajl sistem: " + fs.Text + " · Tabela: " + (gpt ? "GPT" : "MBR") + " · " + (full.Checked ? "pun format" : "brzi format") + " · Naziv: " + lab);
            Log("");

            var t = new Thread(() => RunDiskpart(sb.ToString(), d.Number)) { IsBackground = true };
            t.Start();
        }

        void RunDiskpart(string script, int diskNo)
        {
            string path = Path.Combine(Path.GetTempPath(), "atmosformat_" + Guid.NewGuid().ToString("N") + ".txt");
            int code = -1;
            var sw = Stopwatch.StartNew();
            try
            {
                File.WriteAllText(path, script, Encoding.ASCII);
                var psi = new ProcessStartInfo("diskpart.exe", "/s \"" + path + "\"")
                {
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true,
                };
                try { psi.StandardOutputEncoding = Encoding.GetEncoding(CultureInfo.CurrentCulture.TextInfo.OEMCodePage); } catch { }
                using (var p = Process.Start(psi))
                {
                    var rx = new Regex(@"(\d{1,3})\s*(%|percent|procen|odsto|prozent|pour)", RegexOptions.IgnoreCase);
                    var line = new StringBuilder();
                    int ch;
                    while ((ch = p.StandardOutput.Read()) >= 0)
                    {
                        if (ch == '\r' || ch == '\n')
                        {
                            string l = line.ToString();
                            line.Clear();
                            if (l.Trim().Length == 0) continue;
                            var m = rx.Match(l);
                            if (m.Success)
                            {
                                int pct = Math.Min(100, int.Parse(m.Groups[1].Value));
                                BeginInvoke(new Action(() =>
                                {
                                    progress.Style = ProgressBarStyle.Blocks;
                                    progress.Value = pct;
                                    statusLbl.Text = "Formatiranje: " + pct + "% · " + FormatTime(sw.Elapsed);
                                }));
                                continue;
                            }
                            if (l.StartsWith("Microsoft DiskPart") || l.StartsWith("Copyright") || l.StartsWith("On computer")) continue;
                            Log(l.TrimEnd());
                        }
                        else line.Append((char)ch);
                    }
                    if (line.Length > 0) Log(line.ToString());
                    Log(p.StandardError.ReadToEnd());
                    p.WaitForExit();
                    code = p.ExitCode;
                }
            }
            catch (Exception ex)
            {
                Log("Greška: " + ex.Message);
            }
            finally
            {
                try { File.Delete(path); } catch { }
            }
            var el = sw.Elapsed;
            BeginInvoke(new Action(() =>
            {
                busy = false;
                progress.Style = ProgressBarStyle.Blocks;
                progress.Value = code == 0 ? 100 : 0;
                Reload();
                var d = disks.FirstOrDefault(x => x.Number == diskNo);
                if (code == 0)
                {
                    string letter = d != null && d.Letters.Count > 0 ? " Disk je dostupan kao " + d.LettersText + "." : "";
                    Status("✔ Gotovo za " + FormatTime(el) + "." + letter, OKC);
                    Log("");
                    Log("✔ Formatiranje je uspešno završeno (" + FormatTime(el) + ")." + letter);
                }
                else
                {
                    Status("❗ Formatiranje nije uspelo (kod " + code + "). Pogledaj dnevnik ispod.", BAD);
                    Log("");
                    Log("❗ DiskPart je prijavio grešku (kod " + code + ").");
                    if (full.Checked) Log("Ako je greška nastala tokom punog formata, disk verovatno ima oštećene sektore.");
                }
                UpdateUi();
            }));
        }

        static string FormatTime(TimeSpan t)
        {
            if (t.TotalSeconds < 60) return (int)t.TotalSeconds + " s";
            if (t.TotalMinutes < 60) return (int)t.TotalMinutes + " min " + t.Seconds + " s";
            return (int)t.TotalHours + " h " + t.Minutes + " min";
        }
    }

    /// <summary>Potvrda: korisnik mora da upiše broj diska.</summary>
    sealed class ConfirmForm : Form
    {
        public ConfirmForm(DiskInfo d, string fs, string style, bool full)
        {
            Text = "Potvrda brisanja";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            StartPosition = FormStartPosition.CenterParent;
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);
            Font = new Font("Segoe UI", 9.75F);
            BackColor = MainForm.CARD;
            ForeColor = MainForm.TXT;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Padding = new Padding(18);

            var p = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill };
            p.Controls.Add(new Label { Text = "⚠ SVI PODACI ĆE BITI OBRISANI", Font = new Font("Segoe UI Semibold", 13F), ForeColor = MainForm.BAD, AutoSize = true });
            p.Controls.Add(new Label
            {
                Text = "Disk " + d.Number + ": " + d.Name + "\n" + DiskInfo.SizeText(d.Size) + " · " + d.BusText + (d.Letters.Count > 0 ? " · slova " + d.LettersText : "") +
                       (string.IsNullOrEmpty(d.Serial) ? "" : "\nSerijski broj: " + d.Serial) +
                       "\n\nNovi format: " + fs + ", " + style + ", " + (full ? "pun format" : "brzi format") +
                       (d.Removable ? "" : "\n\nOVO JE INTERNI DISK!"),
                AutoSize = true,
                MaximumSize = new Size(440, 0),
                Margin = new Padding(3, 10, 3, 10),
                ForeColor = d.Removable ? MainForm.TXT : MainForm.WARN
            });
            p.Controls.Add(new Label { Text = "Za potvrdu upiši broj diska (" + d.Number + "):", AutoSize = true, ForeColor = MainForm.MUTED });
            var tb = new TextBox { Width = 120, BackColor = MainForm.CARD2, ForeColor = MainForm.TXT, BorderStyle = BorderStyle.FixedSingle };
            p.Controls.Add(tb);
            var btns = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 14, 0, 0) };
            var ok = new Button();
            MainForm.Style(ok, "Obriši i formatiraj", true);
            ok.BackColor = Color.FromArgb(0xB9, 0x1C, 0x1C);
            ok.FlatAppearance.BorderColor = ok.BackColor;
            ok.Enabled = false;
            var cancel = new Button();
            MainForm.Style(cancel, "Odustani", false);
            cancel.DialogResult = DialogResult.Cancel;
            ok.Click += (s, e) => { DialogResult = DialogResult.OK; Close(); };
            tb.TextChanged += (s, e) => ok.Enabled = tb.Text.Trim() == d.Number.ToString();
            btns.Controls.Add(ok);
            btns.Controls.Add(cancel);
            p.Controls.Add(btns);
            Controls.Add(p);
            CancelButton = cancel;
        }
    }
}
