// ============================================================
// 故障检测系统 · 单文件绿色版启动器（内嵌 jar + JRE，双击即用）
//
// 编译（csc 在 .NET Framework 4 目录下，Windows 自带）：
//   C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe
//     /target:winexe /codepage:65001
//     /r:System.Windows.Forms.dll /r:System.Drawing.dll
//     /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll
//     /resource:payload.zip,payload.zip
//     /out:故障检测系统.exe single-exe-launcher.cs
//
// 工作原理：
//   把 app\（Spring Boot jar）与 runtime\（jlink 精简 JRE）打成 payload.zip 内嵌进本 exe。
//   首次启动解压到 %LOCALAPPDATA%\FaultDetectSystem\payload-<版本>\，其后每次启动直接复用，
//   因此对外只需分发这一个 exe 文件：目标机器无需安装 Java，也无需附带任何文件夹。
//   业务数据落在 %LOCALAPPDATA%\FaultDetectSystem\data\，与 payload 目录分离，升级不丢历史。
// ============================================================
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net.Sockets;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace FaultDetectSingleExe
{
    static class Program
    {
        /// <summary>内嵌资源名（= 打包时 /resource 指定的逻辑名）</summary>
        const string PayloadResource = "payload.zip";
        /// <summary>payload 版本号：改动内嵌内容时必须同步修改，以触发目标机重新解压</summary>
        const string PayloadVersion = "20260924-1";

        const string SiteUrl = "http://localhost:8000";
        const int Port = 8000;
        const string MutexName = "FaultDetectLauncher_Singleton_8000";

        static string WorkRoot;   // 解压根目录（含 app\ 与 runtime\）
        static string JarPath;    // app\fault-detect-system.jar
        static string JavaExe;    // runtime\bin\java.exe
        static string DataDir;    // 业务数据目录

        static NotifyIcon tray;
        static Process javaProc;

        [STAThread]
        static void Main()
        {
            bool createdNew;
            var mutex = new Mutex(true, MutexName, out createdNew);
            using (mutex)
            {
                if (!createdNew)
                {
                    // 已有实例在托盘运行，直接打开界面
                    OpenBrowser();
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                ResolvePaths();

                if (IsPortOpen(Port))
                {
                    // 服务已在运行（可能是本 exe 上次启动的，也可能是绿色版文件夹版）
                    OpenBrowser();
                }
                else
                {
                    // 1) 准备运行环境：首次解压，之后秒过
                    Exception prepError = null;
                    using (var splash = new SplashForm())
                    {
                        splash.Show();
                        splash.Refresh();
                        try { PreparePayload(splash); }
                        catch (Exception ex) { prepError = ex; }
                        splash.Close();
                    }
                    if (prepError != null)
                    {
                        MessageBox.Show(
                            "运行环境准备失败：\n" + prepError.Message +
                            "\n\n解压目录：" + WorkRoot,
                            "启动失败");
                        return;
                    }

                    // 2) 拉起 Java 服务
                    javaProc = StartJava();
                    if (javaProc == null) return;   // 错误已提示

                    // 3) 等服务就绪后打开浏览器
                    if (WaitPort(Port, 40)) OpenBrowser();
                    else MessageBox.Show("服务启动超时（超过 40 秒）。\n可尝试手动执行：\n\""
                            + JavaExe + "\" -jar \"" + JarPath + "\"", "启动超时");
                }

                SetupTray();
                Application.Run();
                tray.Visible = false;
            }
        }

        // ================= 路径解析 =================

        static void ResolvePaths()
        {
            string baseDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "FaultDetectSystem");
            WorkRoot = Path.Combine(baseDir, "payload-" + PayloadVersion);
            JarPath = Path.Combine(WorkRoot, "app", "fault-detect-system.jar");
            JavaExe = Path.Combine(WorkRoot, "runtime", "bin", "java.exe");
            DataDir = Path.Combine(baseDir, "data");
        }

        // ================= 首次解压内嵌运行环境 =================

        static void PreparePayload(SplashForm splash)
        {
            string stamp = Path.Combine(WorkRoot, ".extracted-ok");

            // 已解压过且关键文件齐全 —— 直接复用，启动只需等 Java 起服务
            if (File.Exists(stamp) && File.Exists(JarPath) && File.Exists(JavaExe)) return;

            splash.SetText("首次启动，正在展开运行环境，请稍候…");

            // 上次解压残留（例如中途断电）一律清掉重来，避免半成品被当成完整环境
            if (Directory.Exists(WorkRoot)) Directory.Delete(WorkRoot, true);
            Directory.CreateDirectory(WorkRoot);

            Assembly asm = Assembly.GetExecutingAssembly();
            using (Stream raw = asm.GetManifestResourceStream(PayloadResource))
            {
                if (raw == null)
                {
                    throw new Exception("内置运行环境缺失（未找到资源 " + PayloadResource + "）");
                }
                using (var zip = new ZipArchive(raw, ZipArchiveMode.Read))
                {
                    int total = zip.Entries.Count;
                    int done = 0;
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        string dest = Path.Combine(WorkRoot,
                                entry.FullName.Replace('/', Path.DirectorySeparatorChar));
                        if (string.IsNullOrEmpty(entry.Name))
                        {
                            // 目录条目
                            Directory.CreateDirectory(dest);
                        }
                        else
                        {
                            string parent = Path.GetDirectoryName(dest);
                            if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
                            entry.ExtractToFile(dest, true);
                        }
                        done++;
                        if (done % 60 == 0 || done == total)
                        {
                            splash.SetText("正在展开运行环境… " + (done * 100 / total) + "%");
                        }
                    }
                }
            }

            File.WriteAllText(stamp, PayloadVersion);
        }

        // ================= 启动 Java =================

        static Process StartJava()
        {
            if (!File.Exists(JarPath))
            {
                MessageBox.Show("未找到系统文件：\n" + JarPath, "启动失败");
                return null;
            }
            if (!File.Exists(JavaExe))
            {
                MessageBox.Show("未找到内置 Java 运行环境：\n" + JavaExe, "启动失败");
                return null;
            }
            try
            {
                var psi = new ProcessStartInfo(JavaExe, "-jar \"" + JarPath + "\"");
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.WorkingDirectory = WorkRoot;
                // 数据目录固定，不随 payload 版本变化，升级后历史记录仍在
                psi.EnvironmentVariables["FAULT_DETECT_DATA_DIR"] = DataDir;
                return Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show("启动失败：" + ex.Message, "错误");
                return null;
            }
        }

        // ================= 托盘 =================

        static void SetupTray()
        {
            tray = new NotifyIcon();
            tray.Icon = SystemIcons.Application;
            tray.Text = "故障检测系统（运行中）";
            tray.DoubleClick += (s, e) => OpenBrowser();

            var menu = new ContextMenuStrip();
            menu.Items.Add("打开界面", null, (s, e) => OpenBrowser());
            menu.Items.Add("-");
            menu.Items.Add("退出系统", null, (s, e) => ExitApp());
            tray.ContextMenuStrip = menu;
            tray.Visible = true;
        }

        // ================= 工具方法 =================

        /// <summary>端口是否已监听（服务是否在跑）</summary>
        static bool IsPortOpen(int port)
        {
            try
            {
                using (var c = new TcpClient())
                {
                    var ar = c.BeginConnect("127.0.0.1", port, null, null);
                    if (ar.AsyncWaitHandle.WaitOne(600))
                    {
                        c.EndConnect(ar);
                        return true;
                    }
                }
            }
            catch { }
            return false;
        }

        /// <summary>轮询等待端口就绪</summary>
        static bool WaitPort(int port, int seconds)
        {
            for (int i = 0; i < seconds; i++)
            {
                if (IsPortOpen(port)) return true;
                Thread.Sleep(1000);
            }
            return false;
        }

        static void OpenBrowser()
        {
            try
            {
                Process.Start(new ProcessStartInfo(SiteUrl) { UseShellExecute = true });
            }
            catch
            {
                MessageBox.Show("请手动打开：" + SiteUrl, "提示");
            }
        }

        static void ExitApp()
        {
            // 仅停止由本启动器拉起的 jar 进程
            try
            {
                if (javaProc != null && !javaProc.HasExited) javaProc.Kill();
            }
            catch { }
            if (tray != null) tray.Visible = false;
            Application.Exit();
        }
    }

    /// <summary>无边框提示窗：首次解压耗时较长，需要一个可见反馈，避免用户以为没反应</summary>
    class SplashForm : Form
    {
        readonly Label label;

        public SplashForm()
        {
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            Size = new Size(440, 96);
            BackColor = Color.FromArgb(23, 30, 46);
            ShowInTaskbar = true;
            TopMost = true;

            label = new Label();
            label.Dock = DockStyle.Fill;
            label.ForeColor = Color.White;
            label.TextAlign = ContentAlignment.MiddleCenter;
            label.Font = UiFont();
            label.Text = "故障检测系统 · 正在准备…";
            Controls.Add(label);
        }

        static Font UiFont()
        {
            try { return new Font("Microsoft YaHei", 10.5F); }
            catch { return SystemFonts.MessageBoxFont; }
        }

        public void SetText(string text)
        {
            label.Text = text;
            Refresh();
            Application.DoEvents();   // 立即重绘，否则解压期间界面不刷新
        }
    }
}
