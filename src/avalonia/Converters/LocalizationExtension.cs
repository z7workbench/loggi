using Avalonia;
using Avalonia.Controls;
using Avalonia.Data;
using Avalonia.Markup.Xaml;
using System;
using top.z7workbench.loggi.Services;

namespace top.z7workbench.loggi.Converters
{
    public class LocalizationExtension : MarkupExtension
    {
        public string Key { get; set; }
        
        public object ArgsSingle { get; set; }  // For single argument binding

        public LocalizationExtension()
        {
        }

        public LocalizationExtension(string key)
        {
            Key = key;
        }

        public override object ProvideValue(IServiceProvider serviceProvider)
        {
            // Try to get the localization service from the main window view model first
            var desktopLifetime = Avalonia.Application.Current?.ApplicationLifetime as Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime;
            if (desktopLifetime?.MainWindow?.DataContext is ViewModels.MainWindowViewModel mainViewModel)
            {
                var localizationService = mainViewModel.GetLocalizationService();
                if (localizationService != null)
                {
                    return GetLocalizedValue(localizationService);
                }
            }
            
            // Fallback to the static localization manager which has the current language settings
            try
            {
                return GetLocalizedValue(LocalizationManager.Current);
            }
            catch
            {
                // Final fallback if everything fails
                return $"[{Key}]";
            }
        }
        
        private object GetLocalizedValue(LocalizationService localizationService)
        {
            if (ArgsSingle != null)
            {
                return localizationService.GetString(Key, new object[] { ArgsSingle });
            }
            else
            {
                return localizationService.GetString(Key);
            }
        }
    }
}