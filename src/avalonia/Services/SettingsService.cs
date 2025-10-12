using System;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;

namespace top.z7workbench.loggi.Services
{
    public class SettingsService
    {
        private readonly string _settingsFilePath;
        private readonly JsonSerializerOptions _jsonOptions;

        public SettingsService()
        {
            // Determine the settings file path based on the OS
            string configDir;
            
#if WINDOWS
            configDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Loggi");
#elif LINUX || OSX
            configDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".loggi");
#else
            configDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Loggi");
#endif

            // Ensure the config directory exists
            if (!Directory.Exists(configDir))
            {
                Directory.CreateDirectory(configDir);
            }

            _settingsFilePath = Path.Combine(configDir, "settings.json");
            
            // Configure JSON options
            _jsonOptions = new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true,
                WriteIndented = true
            };
        }

        /// <summary>
        /// Loads application settings from the settings file. Returns default settings if the file doesn't exist or is invalid.
        /// </summary>
        /// <returns>ApplicationSettings object containing the loaded settings</returns>
        public ApplicationSettings LoadSettings()
        {
            try
            {
                if (File.Exists(_settingsFilePath))
                {
                    var json = File.ReadAllText(_settingsFilePath);
                    var settings = JsonSerializer.Deserialize<ApplicationSettings>(json, _jsonOptions);
                    return settings ?? CreateDefaultSettingsWithSystemCulture();
                }
            }
            catch (Exception ex)
            {
                // Log the error (you could use a proper logging framework if available)
                System.Diagnostics.Debug.WriteLine($"Error loading settings: {ex.Message}");
            }

            // Return default settings with system-appropriate language if file doesn't exist or there was an error
            return CreateDefaultSettingsWithSystemCulture();
        }
        
        /// <summary>
        /// Creates default settings with language based on system culture
        /// </summary>
        /// <returns>Default ApplicationSettings with system-appropriate language</returns>
        private ApplicationSettings CreateDefaultSettingsWithSystemCulture()
        {
            var settings = new ApplicationSettings();
            var currentCulture = System.Globalization.CultureInfo.CurrentUICulture;
            // If system culture is Chinese (Simplified), use zh-Hans; otherwise default to en-US
            if (currentCulture.Name.StartsWith("zh"))
            {
                settings.Language = "zh-Hans";
            }
            else
            {
                settings.Language = "en-US";
            }
            return settings;
        }

        /// <summary>
        /// Saves application settings to the settings file.
        /// </summary>
        /// <param name="settings">The settings object to save</param>
        public void SaveSettings(ApplicationSettings settings)
        {
            try
            {
                var json = JsonSerializer.Serialize(settings, _jsonOptions);
                File.WriteAllText(_settingsFilePath, json);
            }
            catch (Exception ex)
            {
                // Log the error (you could use a proper logging framework if available)
                System.Diagnostics.Debug.WriteLine($"Error saving settings: {ex.Message}");
            }
        }

        /// <summary>
        /// Asynchronously loads application settings from the settings file.
        /// </summary>
        /// <returns>Task that completes with the loaded ApplicationSettings object</returns>
        public async Task<ApplicationSettings> LoadSettingsAsync()
        {
            try
            {
                if (File.Exists(_settingsFilePath))
                {
                    var json = await File.ReadAllTextAsync(_settingsFilePath);
                    var settings = JsonSerializer.Deserialize<ApplicationSettings>(json, _jsonOptions);
                    return settings ?? CreateDefaultSettingsWithSystemCulture();
                }
            }
            catch (Exception ex)
            {
                // Log the error (you could use a proper logging framework if available)
                System.Diagnostics.Debug.WriteLine($"Error loading settings: {ex.Message}");
            }

            // Return default settings with system-appropriate language if file doesn't exist or there was an error
            return CreateDefaultSettingsWithSystemCulture();
        }

        /// <summary>
        /// Asynchronously saves application settings to the settings file.
        /// </summary>
        /// <param name="settings">The settings object to save</param>
        /// <returns>Task that completes when saving is finished</returns>
        public async Task SaveSettingsAsync(ApplicationSettings settings)
        {
            try
            {
                var json = JsonSerializer.Serialize(settings, _jsonOptions);
                await File.WriteAllTextAsync(_settingsFilePath, json);
            }
            catch (Exception ex)
            {
                // Log the error (you could use a proper logging framework if available)
                System.Diagnostics.Debug.WriteLine($"Error saving settings: {ex.Message}");
            }
        }
    }

    /// <summary>
    /// Data structure representing the application settings
    /// </summary>
    public class ApplicationSettings
    {
        public double FontSize { get; set; } = 12.0;
        public string FontFamily { get; set; } = "Consolas";
        public bool ShowLineNumbers { get; set; } = true;
        public bool WordWrap { get; set; } = false;
        public string LastOpenedFile { get; set; } = string.Empty;
        public double LineHeight { get; set; } = 1.2; // Default line height multiplier
        public string Language { get; set; } = "en-US"; // Default language
        public string Theme { get; set; } = "ZeroGo's Dark"; // Default theme
    }
}