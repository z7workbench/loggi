using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Styling;
using System;
using System.Collections.Generic;
using System.Linq;

namespace top.z7workbench.loggi.Services
{
    public enum ThemeType
    {
        Light,
        Dark
    }

    public class ThemeService
    {
        private static ThemeService _instance;
        public static ThemeService Instance => _instance ??= new ThemeService();

        public ThemeType CurrentTheme { get; private set; } = ThemeType.Dark; // Default to dark
        public event EventHandler<ThemeType> ThemeChanged;

        private ThemeService() { }

        public void Initialize()
        {
            // Load the theme from settings at startup
            var settingsService = new SettingsService();
            var settings = settingsService.LoadSettings();
            
            if (settings.Theme == "ZeroGo's Light")
            {
                CurrentTheme = ThemeType.Light;
            }
            else if (settings.Theme == "ZeroGo's Dark")
            {
                CurrentTheme = ThemeType.Dark;
            }
            else
            {
                // Default to dark if theme setting is invalid
                CurrentTheme = ThemeType.Dark;
                settings.Theme = "ZeroGo's Dark";
                settingsService.SaveSettings(settings);
            }
            
            // Apply the loaded theme
            ApplyTheme(CurrentTheme);
        }

        public void SetTheme(ThemeType theme)
        {
            if (CurrentTheme == theme) return;

            CurrentTheme = theme;
            ApplyTheme(theme);
            
            // Save the theme to settings when changed programmatically
            var settingsService = new SettingsService();
            var settings = settingsService.LoadSettings();
            
            if (theme == ThemeType.Light)
            {
                settings.Theme = "ZeroGo's Light";
            }
            else
            {
                settings.Theme = "ZeroGo's Dark";
            }
            
            settingsService.SaveSettings(settings);
            
            // Notify listeners of theme change
            ThemeChanged?.Invoke(this, theme);
        }

        public void ToggleTheme()
        {
            var newTheme = CurrentTheme == ThemeType.Light ? ThemeType.Dark : ThemeType.Light;
            SetTheme(newTheme);
        }

        private void ApplyTheme(ThemeType theme)
        {
            if (Application.Current != null)
            {
                var resources = Application.Current.Resources;
                
                if (theme == ThemeType.Light)
                {
                    // Apply light theme colors 
                    resources["PrimaryColor"] = Color.Parse("#8686ff");
                    resources["SecondaryColor"] = Color.Parse("#ff8d00");  // Note: Theme files originally had orange colors here
                    resources["MainBackground"] = Color.Parse("#FFFFFF");
                    resources["TextColor"] = Color.Parse("#000000");
                    resources["NonPrimaryBackground"] = Color.Parse("#EDEDED");
                    resources["SeparatorColor"] = Color.Parse("#AEAEAE");
                    
                    // Apply light theme brushes
                    resources["PrimaryBrush"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    resources["SecondaryBrush"] = new SolidColorBrush(Color.Parse("#ff8d00"));
                    resources["MainBackgroundBrush"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["TextBrush"] = new SolidColorBrush(Color.Parse("#000000"));
                    resources["NonPrimaryBackgroundBrush"] = new SolidColorBrush(Color.Parse("#EDEDED"));
                    resources["SeparatorBrush"] = new SolidColorBrush(Color.Parse("#AEAEAE"));
                    
                    // Apply UI specific light theme brushes (SectionHeaderBackground updated per user request)
                    resources["StatusBarBackground"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    resources["SectionHeaderBackground"] = new SolidColorBrush(Color.Parse("#EDEDED"));  // Changed from orange to NonPrimaryBackground
                    resources["MainContentBackground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["LineNumberForeground"] = new SolidColorBrush(Color.Parse("#AEAEAE"));
                    resources["SearchBoxBackground"] = new SolidColorBrush(Color.Parse("#EDEDED"));
                    resources["SearchBoxForeground"] = new SolidColorBrush(Color.Parse("#000000"));
                    resources["SearchBoxBorder"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    resources["SearchButtonBackground"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    resources["SearchButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["ClearButtonBackground"] = new SolidColorBrush(Color.Parse("#ff8d00"));
                    resources["ClearButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["NavigationSidebarBackground"] = new SolidColorBrush(Color.Parse("#EDEDED"));
                    resources["NavigationSidebarBorder"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    resources["NavigationButtonForeground"] = new SolidColorBrush(Color.Parse("#000000"));
                    resources["SelectedButtonBackground"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    resources["SelectedButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["ButtonHoverBackground"] = new SolidColorBrush(Color.Parse("#ff8d00"));
                    resources["SeparatorBorder"] = new SolidColorBrush(Color.Parse("#AEAEAE"));
                    resources["SystemControlBackgroundBrush"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["SystemControlForegroundBrush"] = new SolidColorBrush(Color.Parse("#000000"));
                    resources["SystemControlBorderBrush"] = new SolidColorBrush(Color.Parse("#8686ff"));
                    
                    // Apply window chrome colors for light theme
                    resources["SystemChromeBackgroundColor"] = Color.Parse("#f0f0f0");
                    resources["SystemChromeForegroundColor"] = Color.Parse("#000000");
                    resources["SystemChromeMediumColor"] = Color.Parse("#e6e6e6");
                    resources["SystemChromeHighColor"] = Color.Parse("#d0d0d0");
                    resources["SystemChromeBackgroundBrush"] = new SolidColorBrush(Color.Parse("#f0f0f0"));
                    resources["SystemChromeForegroundBrush"] = new SolidColorBrush(Color.Parse("#000000"));
                    resources["SystemChromeMediumBrush"] = new SolidColorBrush(Color.Parse("#e6e6e6"));
                    resources["SystemChromeHighBrush"] = new SolidColorBrush(Color.Parse("#d0d0d0"));
                }
                else
                {
                    // Apply dark theme colors
                    resources["PrimaryColor"] = Color.Parse("#583dbf");
                    resources["SecondaryColor"] = Color.Parse("#cd6e1c");  // Note: Theme files originally had orange colors here
                    resources["MainBackground"] = Color.Parse("#1e1e1e");
                    resources["TextColor"] = Color.Parse("#FFFFFF");
                    resources["NonPrimaryBackground"] = Color.Parse("#404040");
                    resources["SeparatorColor"] = Color.Parse("#EDEDED");
                    
                    // Apply dark theme brushes
                    resources["PrimaryBrush"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    resources["SecondaryBrush"] = new SolidColorBrush(Color.Parse("#cd6e1c"));
                    resources["MainBackgroundBrush"] = new SolidColorBrush(Color.Parse("#1e1e1e"));
                    resources["TextBrush"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["NonPrimaryBackgroundBrush"] = new SolidColorBrush(Color.Parse("#404040"));
                    resources["SeparatorBrush"] = new SolidColorBrush(Color.Parse("#EDEDED"));
                    
                    // Apply UI specific dark theme brushes (SectionHeaderBackground updated per user request)
                    resources["StatusBarBackground"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    resources["SectionHeaderBackground"] = new SolidColorBrush(Color.Parse("#404040"));  // Changed from #AEAEAE to #404040 to match new NonPrimaryBackground
                    resources["MainContentBackground"] = new SolidColorBrush(Color.Parse("#1e1e1e"));
                    resources["LineNumberForeground"] = new SolidColorBrush(Color.Parse("#404040"));  // Changed from #AEAEAE to #404040 for consistency
                    resources["SearchBoxBackground"] = new SolidColorBrush(Color.Parse("#2d2d30"));
                    resources["SearchBoxForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["SearchBoxBorder"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    resources["SearchButtonBackground"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    resources["SearchButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["ClearButtonBackground"] = new SolidColorBrush(Color.Parse("#cd6e1c"));
                    resources["ClearButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["NavigationSidebarBackground"] = new SolidColorBrush(Color.Parse("#404040"));  // Changed from #AEAEAE to #404040 to match new NonPrimaryBackground
                    resources["NavigationSidebarBorder"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    resources["NavigationButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["SelectedButtonBackground"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    resources["SelectedButtonForeground"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["ButtonHoverBackground"] = new SolidColorBrush(Color.Parse("#cd6e1c"));
                    resources["SeparatorBorder"] = new SolidColorBrush(Color.Parse("#EDEDED"));
                    resources["SystemControlBackgroundBrush"] = new SolidColorBrush(Color.Parse("#2d2d30"));
                    resources["SystemControlForegroundBrush"] = new SolidColorBrush(Color.Parse("#FFFFFF"));
                    resources["SystemControlBorderBrush"] = new SolidColorBrush(Color.Parse("#583dbf"));
                    
                    // Apply window chrome colors for dark theme
                    resources["SystemChromeBackgroundColor"] = Color.Parse("#1e1e1e");
                    resources["SystemChromeForegroundColor"] = Color.Parse("#ffffff");
                    resources["SystemChromeMediumColor"] = Color.Parse("#2d2d30");
                    resources["SystemChromeHighColor"] = Color.Parse("#3f3f46");
                    resources["SystemChromeBackgroundBrush"] = new SolidColorBrush(Color.Parse("#1e1e1e"));
                    resources["SystemChromeForegroundBrush"] = new SolidColorBrush(Color.Parse("#ffffff"));
                    resources["SystemChromeMediumBrush"] = new SolidColorBrush(Color.Parse("#2d2d30"));
                    resources["SystemChromeHighBrush"] = new SolidColorBrush(Color.Parse("#3f3f46"));
                }
            }
        }
    }
}