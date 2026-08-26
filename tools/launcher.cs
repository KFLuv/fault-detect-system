// ============================================================
// 故障检测系统 · 桌面一键启动器（方案 B：jar 直跑，脱离 Docker）
// 编译：C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe
//       /target:winexe /codepage:65001 /r:System.Windows.Forms.dll /r:System.Drawing.dll launcher.cs
// ============================================================
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Windows.Forms;

namespace FaultDetectLauncher
{
    static class Program
    {
        const string JarPath = @"E:\Desktop\中广核驻场实战手册\故障检测系统\fault-detect-system\java-backend\target\fault-detect-system.jar";
        const string SiteUrl = "http://localhost:8000";
        const int Port = 8000;
        const string MutexName = "FaultDetectLauncher_Singleton_8000";

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

                bool alreadyRunning = IsPortOpen(Port);

                if (alreadyRunning)
                {
                    // 服务已在运行（可能是之前启动的 jar 或 Docker），直接打开
                    OpenBrowser();
                }
                else
                {
                    javaProc = StartJava();
                    if (javaProc == null) return; // 错误已提示

                    bool ready = WaitPort(Port, 30);
                    if (ready) OpenBrowser();
                    else
                        MessageBox.Show("服务启动超时（超过 30 秒）。请检查 Java 环境后重试。\n也可尝试：java -jar \"" + JarPath + "\"", "启动超时");
                }

                SetupTray();
                Application.Run();
                tray.Visible = false;
            }
        }

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

        /// <summary>启动 jar（优先 JAVA_HOME，其次 PATH）</summary>
        static Process StartJava()
        {
            if (!File.Exists(JarPath))
            {
                MessageBox.Show("未找到系统文件（jar）：\n" + JarPath + "\n请检查项目路径。", "启动失败");
                return null;
            }

            string javaExe = FindJava();
            if (javaExe == null)
            {
                MessageBox.Show("未找到 Java 运行环境。\n请安装 JDK（如 11+）并配置 JAVA_HOME 或 PATH。", "启动失败");
                return null;
            }

            try
            {
                var psi = new ProcessStartInfo(javaExe, "-jar \"" + JarPath + "\"");
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.WorkingDirectory = Path.GetDirectoryName(JarPath);
                return Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show("启动失败：" + ex.Message, "错误");
                return null;
            }
        }

        static string FindJava()
        {
            string home = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(home))
            {
                string p = Path.Combine(home, "bin", "java.exe");
                if (File.Exists(p)) return p;
            }
            try
            {
                // PATH 里的 java
                using (var p = new Process())
                {
                    p.StartInfo.FileName = "where";
                    p.StartInfo.Arguments = "java";
                    p.StartInfo.UseShellExecute = false;
                    p.StartInfo.RedirectStandardOutput = true;
                    p.StartInfo.CreateNoWindow = true;
                    p.Start();
                    string line = p.StandardOutput.ReadLine();
                    p.WaitForExit(3000);
                    if (!string.IsNullOrEmpty(line) && File.Exists(line.Trim())) return line.Trim();
                }
            }
            catch { }
            return null;
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
            // 仅停止由本启动器拉起的 jar 进程（Docker 容器不受影响）
            try
            {
                if (javaProc != null && !javaProc.HasExited) javaProc.Kill();
            }
            catch { }
            if (tray != null) tray.Visible = false;
            Application.Exit();
        }
    }
}
