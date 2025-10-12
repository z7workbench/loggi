using System;
using System.Collections.ObjectModel;
using System.Linq;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace top.z7workbench.loggi.ViewModels
{
    public partial class SettingsWindowViewModel : ViewModelBase
    {
        [ObservableProperty]
        private double _fontSize = 12.0;
        
        [ObservableProperty]
        private string _fontFamily = "Consolas";
        
        [ObservableProperty]
        private bool _showLineNumbers = true;
        
        [ObservableProperty]
        private bool _wordWrap = false;
        
        [ObservableProperty]
        private double _lineHeight = 1.2; // Default line height multiplier
        
        [ObservableProperty]
        private string _selectedLanguage = "en-US";
        
        [ObservableProperty]
        private string _selectedTheme = "ZeroGo's Dark"; // Will be updated in constructor
        
        public ObservableCollection<string> AvailableFonts { get; set; } = new ObservableCollection<string>();
        public ObservableCollection<string> AvailableLanguages { get; set; } = new ObservableCollection<string>();
        public ObservableCollection<string> AvailableThemes { get; set; } = new ObservableCollection<string> { "ZeroGo's Light", "ZeroGo's Dark" };
        
        /// <summary>
        /// Initializes a new instance of the SettingsWindowViewModel class, loading available system fonts.
        /// </summary>
        public SettingsWindowViewModel()
        {
            LoadAvailableFonts();
            LoadAvailableLanguages();
            
            // Load selected theme from settings
            var settingsService = new Services.SettingsService();
            var settings = settingsService.LoadSettings();
            SelectedTheme = settings.Theme;
        }
        
        /// <summary>
        /// Loads all available system fonts into the AvailableFonts collection and sets the default font.
        /// </summary>
        private void LoadAvailableFonts()
        {
            try
            {
                // Get all available font families from the system
                var fontFamilies = FontManager.Current.SystemFonts
                    .Select(f => f.Name)
                    .OrderBy(name => name)
                    .ToList();

                AvailableFonts.Clear();
                foreach (var fontFamily in fontFamilies)
                {
                    AvailableFonts.Add(fontFamily);
                }
                
                // Set default font family to Consolas if available, otherwise use first available
                if (AvailableFonts.Contains("Consolas"))
                {
                    FontFamily = "Consolas";
                }
                else if (AvailableFonts.Count > 0)
                {
                    FontFamily = AvailableFonts[0];
                }
            }
            catch (Exception ex)
            {
                // Fallback to common fonts if there's an error
                AvailableFonts.Add("Consolas");
                AvailableFonts.Add("Courier New");
                AvailableFonts.Add("Monaco");
                AvailableFonts.Add("Menlo");
                AvailableFonts.Add("Source Code Pro");
                FontFamily = "Consolas";
            }
        }
        
        /// <summary>
        /// Loads available languages for the application.
        /// </summary>
        private void LoadAvailableLanguages()
        {
            AvailableLanguages.Clear();
            AvailableLanguages.Add("en-US");
            AvailableLanguages.Add("zh-Hans");
        }
        
        // For this implementation, we don't need Save/Cancel commands because settings are applied immediately
        // The changes are applied automatically via data binding
        
        /// <summary>
        /// Callback method called when the FontFamily property changes.
        /// </summary>
        /// <param name="value">The new font family value</param>
        partial void OnFontFamilyChanged(string value)
        {
        }
        
        /// <summary>
        /// Callback method called when the FontSize property changes.
        /// </summary>
        /// <param name="value">The new font size value</param>
        partial void OnFontSizeChanged(double value)
        {
        }
        
        /// <summary>
        /// Callback method called when the ShowLineNumbers property changes.
        /// </summary>
        /// <param name="value">The new show line numbers value</param>
        partial void OnShowLineNumbersChanged(bool value)
        {
        }
        
        /// <summary>
        /// Callback method called when the WordWrap property changes.
        /// </summary>
        /// <param name="value">The new word wrap value</param>
        partial void OnWordWrapChanged(bool value)
        {
        }
        
        /// <summary>
        /// Callback method called when the LineHeight property changes.
        /// </summary>
        /// <param name="value">The new line height value</param>
        partial void OnLineHeightChanged(double value)
        {
        }
        
        /// <summary>
        /// Callback method called when the SelectedLanguage property changes.
        /// </summary>
        /// <param name="value">The new language value</param>
        partial void OnSelectedLanguageChanged(string value)
        {
        }
        
        /// <summary>
        /// Callback method called when the SelectedTheme property changes.
        /// </summary>
        /// <param name="value">The new theme value</param>
        partial void OnSelectedThemeChanged(string value)
        {
            // Save the theme to settings service when changed
            var settingsService = new Services.SettingsService();
            var settings = settingsService.LoadSettings();
            settings.Theme = value;
            settingsService.SaveSettings(settings);
            
            // Apply the theme
            if (value == "ZeroGo's Light")
            {
                Services.ThemeService.Instance.SetTheme(Services.ThemeType.Light);
            }
            else if (value == "ZeroGo's Dark")
            {
                Services.ThemeService.Instance.SetTheme(Services.ThemeType.Dark);
            }
        }
        
        /// <summary>
        /// Cancels the settings changes. This command is not fully implemented as settings are applied immediately via data binding.
        /// </summary>
        [RelayCommand]
        private void CancelSettings()
        {
            // Close the window without saving changes would be handled in the view
        }
    }
}