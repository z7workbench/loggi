using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using top.z7workbench.loggi.Services;
using System.Globalization;

namespace top.z7workbench.loggi
{
    public partial class App : Application
    {
        /// <summary>
        /// Initializes the Avalonia XAML loader to load the application's XAML resources.
        /// </summary>
        public override void Initialize()
        {
            AvaloniaXamlLoader.Load(this);
        }

        /// <summary>
        /// Called when the application framework initialization is completed. Sets up the main window with its view model.
        /// </summary>
        public override void OnFrameworkInitializationCompleted()
        {
            if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
            {
                var mainWindowViewModel = new ViewModels.MainWindowViewModel();
                desktop.MainWindow = new Views.MainWindow
                {
                    DataContext = mainWindowViewModel
                };
                
                // Subscribe to language changes to update the UI
                LocalizationManager.LanguageChanged += OnLanguageChanged;
            }

            base.OnFrameworkInitializationCompleted();
        }
        
        private void OnLanguageChanged(CultureInfo culture)
        {
            // Refresh the main window to update localized content
            if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
            {
                // The GlobalizationService will handle the property changes automatically
                // when LocalizationManager.LanguageChanged is triggered
            }
        }
    }
}