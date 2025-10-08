using System;
using System.Globalization;
using System.Resources;

namespace top.z7workbench.loggi.Services
{
    public class LocalizationService
    {
        private ResourceManager _resourceManager;
        private CultureInfo _currentCulture;

        public event Action<CultureInfo> LanguageChanged;

        public LocalizationService()
        {
            // Initialize the resource manager with the default resource file
            _resourceManager = new ResourceManager("top.z7workbench.loggi.Resources.Strings", 
                typeof(LocalizationService).Assembly);
            
            // Default to English initially, but this will be overridden by settings in MainWindowViewModel
            _currentCulture = CultureInfo.GetCultureInfo("en-US");
        }

        public void SetLanguage(CultureInfo culture)
        {
            if (_currentCulture.Name != culture.Name)
            {
                _currentCulture = culture;
                LanguageChanged?.Invoke(_currentCulture);
            }
        }

        public void SetLanguage(string cultureName)
        {
            try
            {
                var culture = CultureInfo.GetCultureInfo(cultureName);
                SetLanguage(culture);
            }
            catch (CultureNotFoundException)
            {
                // If the culture is not found, default to en-US
                var culture = CultureInfo.GetCultureInfo("en-US");
                SetLanguage(culture);
            }
        }

        public CultureInfo GetCurrentCulture()
        {
            return _currentCulture;
        }

        public string GetString(string key)
        {
            try
            {
                string value = _resourceManager.GetString(key, _currentCulture);
                return value ?? $"[{key}]"; // Return the key in brackets if translation is not found
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting string for key '{key}': {ex.Message}");
                return $"[{key}]"; // Return the key in brackets if there's an error
            }
        }

        public string GetString(string key, params object[] args)
        {
            var template = GetString(key);
            if (args.Length > 0)
            {
                try
                {
                    return string.Format(template, args);
                }
                catch
                {
                    return template; // Return the template if formatting fails
                }
            }
            return template;
        }
    }
}