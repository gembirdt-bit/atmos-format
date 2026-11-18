using System;
using System.Collections.Generic;
using System.Linq;
using System.Management;

namespace AtmosFormat
{
    sealed class DiskInfo
    {
        public int Number;
        public string Name = "";
        public string Serial = "";
        public ulong Size;
        public int PartitionStyle;   // 0 prazan, 1 MBR, 2 GPT
        public int BusType;
        public bool IsBoot, IsSystem, IsOffline, IsReadOnly;
        public List<char> Letters = new List<char>();

        public bool Protected { get { return IsBoot || IsSystem; } }

        public bool Removable
        {
            get { return BusType == 7 || BusType == 12 || BusType == 13; } // USB, SD, MMC
        }

        public string BusText
        {
            get
            {
                switch (BusType)
                {
                    case 1: return "SCSI";
                    case 3: return "ATA";
                    case 7: return "USB";
                    case 8: return "RAID";
                    case 10: return "SAS";
                    case 11: return "SATA";
                    case 12: return "SD";
                    case 13: return "MMC";
                    case 15: return "Virtuelni";
                    case 17: return "NVMe";
                    default: return "Ostalo";
                }
            }
        }

        public string StyleText
        {
            get { return PartitionStyle == 1 ? "MBR" : PartitionStyle == 2 ? "GPT" : "Prazan"; }
        }

        public string LettersText
        {
            get { return Letters.Count == 0 ? "—" : string.Join(", ", Letters.Select(c => c + ":")); }
        }

        public static string SizeText(ulong b)
        {
            double v = b;
            if (v >= 1e12) return (v / 1e12).ToString("0.00") + " TB";
            if (v >= 1e9) return (v / 1e9).ToString("0.0") + " GB";
            if (v >= 1e6) return (v / 1e6).ToString("0") + " MB";
            return (v / 1e3).ToString("0") + " KB";
        }

        public static List<DiskInfo> Load()
        {
            var list = new List<DiskInfo>();
            var scope = new ManagementScope(@"\\.\root\Microsoft\Windows\Storage");
            scope.Connect();
            using (var s = new ManagementObjectSearcher(scope, new ObjectQuery("SELECT * FROM MSFT_Disk")))
            {
                foreach (ManagementObject o in s.Get())
                {
                    var d = new DiskInfo();
                    d.Number = Convert.ToInt32(o["Number"]);
                    d.Name = Str(o["FriendlyName"]);
                    d.Serial = Str(o["SerialNumber"]);
                    d.Size = Convert.ToUInt64(o["Size"] ?? 0UL);
                    d.PartitionStyle = Convert.ToInt32(o["PartitionStyle"] ?? 0);
                    d.BusType = Convert.ToInt32(o["BusType"] ?? 0);
                    d.IsBoot = Bool(o["IsBoot"]);
                    d.IsSystem = Bool(o["IsSystem"]);
                    d.IsOffline = Bool(o["IsOffline"]);
                    d.IsReadOnly = Bool(o["IsReadOnly"]);
                    list.Add(d);
                }
            }
            try
            {
                using (var s = new ManagementObjectSearcher(scope, new ObjectQuery("SELECT DiskNumber, DriveLetter FROM MSFT_Partition")))
                {
                    foreach (ManagementObject o in s.Get())
                    {
                        int n = Convert.ToInt32(o["DiskNumber"]);
                        object dl = o["DriveLetter"];
                        if (dl == null) continue;
                        char c = Convert.ToChar(Convert.ToInt32(dl));
                        if (c < 'A' || c > 'Z') continue;
                        var d = list.FirstOrDefault(x => x.Number == n);
                        if (d != null) d.Letters.Add(c);
                    }
                }
            }
            catch { }
            return list.OrderBy(x => x.Number).ToList();
        }

        static string Str(object o) { return o == null ? "" : o.ToString().Trim(); }

        static bool Bool(object o) { return o != null && Convert.ToBoolean(o); }
    }
}
