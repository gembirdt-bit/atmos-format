using System;
using System.Drawing;
using System.Reflection;
using System.Windows.Forms;

namespace AtmosFormat
{
    /// <summary>☕ Plati mi kafu — isti prozor kao u Atmos aplikacijama.</summary>
    sealed class DonateForm : Form
    {
        const string ACCOUNT = "115000000006810143";
        readonly PictureBox qr = new PictureBox();
        readonly Button[] amounts = new Button[3];
        static readonly string[] AMT = { "200", "500", "1000" };

        public DonateForm()
        {
            Text = "Doniraj autoru";
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
            Padding = new Padding(22, 18, 22, 18);

            var p = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill };
            int w = 340;
            p.Controls.Add(Center(new Label { Text = "☕ Plati mi kafu", Font = new Font("Segoe UI Semibold", 13F), AutoSize = false, Width = w, Height = 30 }));
            p.Controls.Add(Center(new Label
            {
                Text = "Ako Vam se dopadaju moje aplikacije i želite da uplatite neki simboličan iznos za kafu ili ručak :D\n\nMožete donirati na račun:",
                AutoSize = false, Width = w, Height = 72
            }));
            var acc = new TextBox
            {
                Text = ACCOUNT, ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = MainForm.CARD2, ForeColor = MainForm.TXT,
                Font = new Font("Consolas", 12F), TextAlign = HorizontalAlignment.Center, Width = w, Margin = new Padding(3, 4, 3, 6)
            };
            p.Controls.Add(acc);
            p.Controls.Add(Center(new Label { Text = "Željko Ilić\nBeograd", AutoSize = false, Width = w, Height = 40, Font = new Font("Segoe UI Semibold", 9.75F) }));
            var copy = new Button();
            MainForm.Style(copy, "Kopiraj broj računa", true);
            copy.AutoSize = false;
            copy.Width = w;
            copy.Height = 36;
            copy.Click += (s, e) =>
            {
                try { Clipboard.SetText(ACCOUNT); } catch { }
                copy.Text = "Kopirano ✓";
                var t = new Timer { Interval = 1800 };
                t.Tick += (a, b) => { copy.Text = "Kopiraj broj računa"; t.Stop(); t.Dispose(); };
                t.Start();
            };
            p.Controls.Add(copy);

            var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 12, 0, 6) };
            for (int i = 0; i < 3; i++)
            {
                var b = new Button();
                MainForm.Style(b, AMT[i] + " RSD", false);
                b.AutoSize = false;
                b.Width = 104;
                b.Height = 32;
                b.Margin = new Padding(i == 0 ? 3 : 6, 0, 0, 0);
                int k = i;
                b.Click += (s, e) => Pick(k);
                amounts[i] = b;
                row.Controls.Add(b);
            }
            p.Controls.Add(row);

            qr.Size = new Size(220, 220);
            qr.SizeMode = PictureBoxSizeMode.Zoom;
            qr.BackColor = Color.White;
            qr.Padding = new Padding(6);
            qr.Margin = new Padding((w - 220) / 2 + 3, 6, 3, 6);
            p.Controls.Add(qr);
            p.Controls.Add(Center(new Label
            {
                Text = "Izaberi iznos, pa u mobilnoj banci odaberi\n„IPS skeniraj” i skeniraj QR kod\n\nHvala!",
                AutoSize = false, Width = w, Height = 70, ForeColor = MainForm.MUTED
            }));
            Controls.Add(p);
            Pick(1);
        }

        static Label Center(Label l)
        {
            l.TextAlign = ContentAlignment.MiddleCenter;
            return l;
        }

        void Pick(int k)
        {
            for (int i = 0; i < 3; i++)
            {
                amounts[i].BackColor = i == k ? MainForm.ACC : MainForm.CARD2;
                amounts[i].ForeColor = i == k ? Color.White : MainForm.TXT;
            }
            try
            {
                var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("qr" + AMT[k] + ".png");
                if (s != null)
                {
                    var old = qr.Image;
                    qr.Image = Image.FromStream(s);
                    if (old != null) old.Dispose();
                }
            }
            catch { }
        }
    }
}
