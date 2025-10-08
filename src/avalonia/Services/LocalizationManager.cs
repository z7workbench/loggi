using System;
using System.Globalization;

namespace top.z7workbench.loggi.Services
{
    /// <summary>
    /// A static localization manager that provides global access to localization services
    /// </summary>
    public static class LocalizationManager
    {
        private static LocalizationService _localizationService;
        
        static LocalizationManager()
        {
            // Initialize with default settings
            var settingsService = new SettingsService();
            var settings = settingsService.LoadSettings();
            
            _localizationService = new LocalizationService();
            _localizationService.SetLanguage(settings.Language);
            
            // Forward the LanguageChanged event from the internal service
            _localizationService.LanguageChanged += OnLanguageChanged;
        }
        
        /// <summary>
        /// Event raised when the language changes, allowing UI to refresh
        /// </summary>
        public static event Action<CultureInfo> LanguageChanged;
        
        /// <summary>
        /// Gets the current localization service instance
        /// </summary>
        public static LocalizationService Current => _localizationService;
        
        /// <summary>
        /// Updates the language for the application
        /// </summary>
        /// <param name="cultureName">The culture name to set (e.g., "en-US", "zh-Hans")</param>
        public static void SetLanguage(string cultureName)
        {
            _localizationService.SetLanguage(cultureName);
            
            // Also update the settings
            var settingsService = new SettingsService();
            var settings = settingsService.LoadSettings();
            settings.Language = cultureName;
            settingsService.SaveSettings(settings);
        }
        
        /// <summary>
        /// Gets a localized string by key
        /// </summary>
        /// <param name="key">The resource key</param>
        /// <returns>The localized string or the key in brackets if not found</returns>
        public static string GetString(string key)
        {
            return _localizationService.GetString(key);
        }
        
        /// <summary>
        /// Gets a localized string by key with formatting arguments
        /// </summary>
        /// <param name="key">The resource key</param>
        /// <param name="args">Formatting arguments</param>
        /// <returns>The localized string or the key in brackets if not found</returns>
        public static string GetString(string key, params object[] args)
        {
            return _localizationService.GetString(key, args);
        }
        
        /// <summary>
        /// Gets the current culture
        /// </summary>
        /// <returns>The current culture</returns>
        public static CultureInfo GetCurrentCulture()
        {
            return _localizationService.GetCurrentCulture();
        }
        
        private static void OnLanguageChanged(CultureInfo culture)
        {
            LanguageChanged?.Invoke(culture);
        }
    }
}