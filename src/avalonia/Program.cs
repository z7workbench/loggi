using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.ReactiveUI;
using System;

namespace top.z7workbench.loggi
{
    internal class Program
    {
        /// <summary>
        /// Main entry point of the application. Initializes and starts the Avalonia application with classic desktop lifetime.
        /// </summary>
        /// <param name="args">Command line arguments passed to the application</param>
        [STAThread]
        public static void Main(string[] args)
        {
            BuildAvaloniaApp()
                .StartWithClassicDesktopLifetime(args);
        }

        /// <summary>
        /// Configures and builds the Avalonia application with platform detection and tracing enabled.
        /// </summary>
        /// <returns>Configured AppBuilder instance</returns>
        public static AppBuilder BuildAvaloniaApp()
        {
            return AppBuilder.Configure<App>()
                .UsePlatformDetect()
                .LogToTrace()
                .UseReactiveUI();
        }
    }
}