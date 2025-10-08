using System;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Avalonia.Controls;
using Avalonia.Media;
using top.z7workbench.loggi.Services;
using System.Threading.Tasks;
using System.Globalization;

namespace top.z7workbench.loggi.ViewModels
{
    public partial class MainWindowViewModel : ViewModelBase
    {
        private readonly RustInterop _rustInterop;
        private readonly SettingsService _settingsService;
        private readonly LocalizationService _localizationService;
        private string _currentFilePath = string.Empty;
        [ObservableProperty]
        private ObservableCollection<LogLineViewModel> _logLines = new ObservableCollection<LogLineViewModel>();
        
        [ObservableProperty]
        private ObservableCollection<SearchResultViewModel> _searchResults = new ObservableCollection<SearchResultViewModel>();
        
        [ObservableProperty]
        private string _searchQuery = string.Empty;
        
        [ObservableProperty]
        private bool _isCaseSensitive = false;
        
        [ObservableProperty]
        private bool _useRegex = false;
        
        [ObservableProperty]
        private bool _isLoading = false;
        
        [ObservableProperty]
        private int _totalLinesCount = 0;
        
        [ObservableProperty]
        private double _fontSize = 12.0;
        
        [ObservableProperty]
        private FontFamily _fontFamily = new FontFamily("Consolas");
        
        [ObservableProperty]
        private bool _showLineNumbers = true;
        
        [ObservableProperty]
        private bool _wordWrap = false;
        
        [ObservableProperty]
        private TextWrapping _contentTextWrapping = TextWrapping.NoWrap;
        
        [ObservableProperty]
        private double _lineHeight = 1.2; // Default line height multiplier
        
        public string CurrentFilePath 
        { 
            get => _currentFilePath;
            set 
            { 
                if (SetProperty(ref _currentFilePath, value))
                {
                    // Save the updated setting
                    var settings = _settingsService.LoadSettings();
                    settings.LastOpenedFile = value;
                    _settingsService.SaveSettings(settings);
                }
            }
        }
        


        /// <summary>
        /// Initializes a new instance of the MainWindowViewModel class, setting up the Rust interop and loading sample log data.
        /// </summary>
        public MainWindowViewModel()
        {
            _rustInterop = new RustInterop();
            _settingsService = new SettingsService();
            _localizationService = new LocalizationService();
            
            // Load settings from persistent storage first
            var settings = _settingsService.LoadSettings();
            FontSize = settings.FontSize;
            FontFamily = new FontFamily(settings.FontFamily);
            ShowLineNumbers = settings.ShowLineNumbers;
            WordWrap = settings.WordWrap;
            ContentTextWrapping = settings.WordWrap ? TextWrapping.Wrap : TextWrapping.NoWrap;
            LineHeight = settings.LineHeight;
            
            // Set language from settings
            _localizationService.SetLanguage(settings.Language);
            
            // Subscribe to language changes to refresh UI
            _localizationService.LanguageChanged += OnLanguageChanged;
            
            LoadSampleLog(); // For testing purposes
        }
        
        /// <summary>
        /// Loads a sample log file for initial testing purposes. Creates a temporary file with sample log entries if it doesn't exist.
        /// </summary>
        private void LoadSampleLog()
        {
            // First try to load the last opened file if it exists
            var settings = _settingsService.LoadSettings();
            if (!string.IsNullOrEmpty(settings.LastOpenedFile) && File.Exists(settings.LastOpenedFile))
            {
                _currentFilePath = settings.LastOpenedFile;
                LoadEntireLogFile();
                return;
            }
            
            // For initial testing, we'll create a sample file
            string samplePath = Path.Combine(Path.GetTempPath(), "sample.log");
            if (!File.Exists(samplePath))
            {
                var lines = Enumerable.Range(1, 10000)
                    .Select(i => $"[INFO] Line {i}: This is a sample log entry with some details at {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
                File.WriteAllLines(samplePath, lines);
            }
            
            _currentFilePath = samplePath;
            LoadEntireLogFile();
        }
        
        /// <summary>
        /// Asynchronously opens a log file selected by the user through a file picker dialog.
        /// </summary>
        [RelayCommand]
        private async Task OpenFileAsync()
        {
            var window = (Avalonia.Application.Current.ApplicationLifetime as Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime)?.MainWindow;
            if (window != null)
            {
                var result = await window.StorageProvider.OpenFilePickerAsync(new Avalonia.Platform.Storage.FilePickerOpenOptions
                {
                    Title = _localizationService.GetString("FileDialogTitle"),
                    AllowMultiple = false,
                    FileTypeFilter = new[] {
                        new Avalonia.Platform.Storage.FilePickerFileType(_localizationService.GetString("LogFilesFilter")) { Patterns = new[] { "*.log", "*.txt", "*.out", "*.err" } },
                        new Avalonia.Platform.Storage.FilePickerFileType(_localizationService.GetString("AllFilesFilter")) { Patterns = new[] { "*" } }
                    }
                });
                
                if (result != null && result.Count > 0)
                {
                    var filePath = result[0].Path.LocalPath;
                    CurrentFilePath = filePath; // Use property setter to trigger persistence
                    
                    LoadEntireLogFile();
                }
            }
        }
        
        /// <summary>
        /// Refreshes the currently loaded log file by reloading its content.
        /// </summary>
        [RelayCommand]
        private void Refresh()
        {
            if (!string.IsNullOrEmpty(_currentFilePath))
            {
                LoadEntireLogFile();
            }
        }
        
        /// <summary>
        /// Updates the total line count property by querying the Rust interop for the line count of the current file.
        /// </summary>
        private void UpdateTotalLinesCount()
        {
            if (!string.IsNullOrEmpty(_currentFilePath))
            {
                TotalLinesCount = _rustInterop.GetTotalLineCount(_currentFilePath);
            }
        }
        
        /// <summary>
        /// Loads a specific page of log lines from the current file. This method is deprecated in favor of LoadEntireLogFile.
        /// </summary>
        /// <param name="pageNumber">The page number to load (not used in the current implementation)</param>
        private void LoadLogPage(int pageNumber)
        {
            // This method is now deprecated, but kept for compatibility
            // We'll use LoadEntireLogFile instead
        }
        
        /// <summary>
        /// Loads the entire content of the current log file in chunks to prevent memory issues.
        /// </summary>
        private void LoadEntireLogFile()
        {
            if (string.IsNullOrEmpty(_currentFilePath) || !File.Exists(_currentFilePath)) return;
            
            // Clear all color marks when loading a new file
            ClearAllColorMarks();
            
            IsLoading = true;
            try
            {
                // First, get the total line count
                var totalLines = _rustInterop.GetTotalLineCount(_currentFilePath);
                TotalLinesCount = totalLines;
                
                LogLines.Clear();
                
                // Load the entire file in chunks to avoid memory issues
                int currentPage = 0;
                const int linesPerChunk = 1000; // Process in chunks but display all at once
                
                while (currentPage * linesPerChunk < totalLines)
                {
                    var response = _rustInterop.ReadLogLines(_currentFilePath, 
                        currentPage * linesPerChunk, 
                        Math.Min(linesPerChunk, totalLines - (currentPage * linesPerChunk)));
                    
                    for (int i = 0; i < response.Lines.Count; i++)
                    {
                        LogLines.Add(new LogLineViewModel
                        {
                            LineNumber = currentPage * linesPerChunk + i + 1,
                            Content = response.Lines[i],
                            IsHighlighted = false,
                            ViewModel = this // Reference to parent view model for context menu commands
                        });
                    }
                    
                    currentPage++;
                }
            }
            finally
            {
                IsLoading = false;
            }
        }
        
        /// <summary>
        /// Performs a search in the current log file based on the search query. Can perform either text search or regex search.
        /// </summary>
        [RelayCommand]
        private void Search()
        {
            Console.WriteLine($"Starting search for: '{SearchQuery}' in file: '{_currentFilePath}'");
            System.Diagnostics.Debug.WriteLine($"Starting search for: '{SearchQuery}' in file: '{_currentFilePath}'");
            
            if (string.IsNullOrWhiteSpace(SearchQuery) || string.IsNullOrEmpty(_currentFilePath)) 
            {
                Console.WriteLine($"Search cancelled - query: '{SearchQuery}', file: '{_currentFilePath}'");
                System.Diagnostics.Debug.WriteLine($"Search cancelled - query: '{SearchQuery}', file: '{_currentFilePath}'");
                return;
            }
            
            IsLoading = true;
            try
            {
                SearchResults.Clear();
                
                SearchResponse response;
                if (UseRegex)
                {
                    Console.WriteLine($"Starting regex search for pattern: '{SearchQuery}', case sensitive: {IsCaseSensitive}");
                    System.Diagnostics.Debug.WriteLine($"Starting regex search for pattern: '{SearchQuery}', case sensitive: {IsCaseSensitive}");
                    response = _rustInterop.RegexSearchLogLines(_currentFilePath, SearchQuery, IsCaseSensitive);
                }
                else
                {
                    Console.WriteLine($"Starting text search for: '{SearchQuery}', case sensitive: {IsCaseSensitive}");
                    System.Diagnostics.Debug.WriteLine($"Starting text search for: '{SearchQuery}', case sensitive: {IsCaseSensitive}");
                    response = _rustInterop.SearchLogLines(_currentFilePath, SearchQuery, IsCaseSensitive);
                }
                
                Console.WriteLine($"Received response from Rust library: {response != null}");
                System.Diagnostics.Debug.WriteLine($"Received response from Rust library: {response != null}");
                if (response != null)
                {
                    Console.WriteLine($"Number of matches: {response.Matches?.Count ?? 0}");
                    System.Diagnostics.Debug.WriteLine($"Number of matches: {response.Matches?.Count ?? 0}");
                }
                
                if (response?.Matches == null)
                {
                    // Log that we got a null response
                    Console.WriteLine("Search response is null or matches is null");
                    System.Diagnostics.Debug.WriteLine("Search response is null or matches is null");
                    // Update status property in the ViewModel
                    IsLoading = false;
                    return;
                }
                
                foreach (var match in response.Matches)
                {
                    Console.WriteLine($"Adding match: Line {match.LineNumber}, Content: {match.Content.Substring(0, Math.Min(50, match.Content.Length))}");
                    System.Diagnostics.Debug.WriteLine($"Adding match: Line {match.LineNumber}, Content: {match.Content.Substring(0, Math.Min(50, match.Content.Length))}");
                    // Get the original log line to preserve color marking
                    var originalLogLine = LogLines.FirstOrDefault(l => l.LineNumber == match.LineNumber);
                    SearchResults.Add(new SearchResultViewModel
                    {
                        LineNumber = match.LineNumber,
                        Content = match.Content.Length > 100 ? match.Content.Substring(0, 100) + "..." : match.Content,
                        ColorMark = originalLogLine?.ColorMark ?? string.Empty, // Preserve original color marking
                        ViewModel = this // Reference to parent view model for context menu commands
                    });
                }
                
                Console.WriteLine($"Found {response.Matches.Count} search results");
                System.Diagnostics.Debug.WriteLine($"Found {response.Matches.Count} search results");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Search error: {ex.Message}");
                System.Diagnostics.Debug.WriteLine($"Search error: {ex.Message}");
                // Log the full exception details
                Console.WriteLine($"Full exception: {ex}");
                System.Diagnostics.Debug.WriteLine($"Full exception: {ex}");
                Console.WriteLine($"Stack trace: {ex.StackTrace}");
                System.Diagnostics.Debug.WriteLine($"Stack trace: {ex.StackTrace}");
            }
            finally
            {
                IsLoading = false;
            }
        }
        
        /// <summary>
        /// Clears the current search query and search results.
        /// </summary>
        [RelayCommand]
        private void ClearResults()
        {
            SearchQuery = string.Empty;
            SearchResults.Clear();
        }
        
        /// <summary>
        /// Opens the settings window and handles updates to settings that affect the display of log content.
        /// </summary>
        [RelayCommand]
        private void Settings()
        {
            // This will open the settings window
            var settingsWindow = new SettingsWindowViewModel();
            settingsWindow.FontSize = FontSize;
            settingsWindow.FontFamily = FontFamily.Name; // Convert FontFamily to its name string
            settingsWindow.ShowLineNumbers = ShowLineNumbers;
            settingsWindow.WordWrap = WordWrap;
            settingsWindow.LineHeight = LineHeight;
            settingsWindow.SelectedLanguage = _localizationService.GetCurrentCulture().Name; // Set current language
            
            bool settingsChanged = false;
            
            // Subscribe to changes in the settings
            settingsWindow.PropertyChanged += (sender, e) =>
            {
                if (e.PropertyName == nameof(SettingsWindowViewModel.FontSize))
                {
                    FontSize = settingsWindow.FontSize;
                    settingsChanged = true;
                }
                else if (e.PropertyName == nameof(SettingsWindowViewModel.FontFamily))
                {
                    FontFamily = new FontFamily(settingsWindow.FontFamily); // Convert string back to FontFamily
                    settingsChanged = true;
                }
                else if (e.PropertyName == nameof(SettingsWindowViewModel.ShowLineNumbers))
                {
                    ShowLineNumbers = settingsWindow.ShowLineNumbers;
                    settingsChanged = true;
                }
                else if (e.PropertyName == nameof(SettingsWindowViewModel.WordWrap))
                {
                    WordWrap = settingsWindow.WordWrap;
                    settingsChanged = true;
                }
                else if (e.PropertyName == nameof(SettingsWindowViewModel.LineHeight))
                {
                    LineHeight = settingsWindow.LineHeight;
                    settingsChanged = true;
                }
                else if (e.PropertyName == nameof(SettingsWindowViewModel.SelectedLanguage))
                {
                    // Update language when it changes
                    string oldLanguage = _localizationService.GetCurrentCulture().Name;
                    _localizationService.SetLanguage(settingsWindow.SelectedLanguage);
                    
                    // If language actually changed, trigger UI refresh for localization
                    if (oldLanguage != settingsWindow.SelectedLanguage)
                    {
                        settingsChanged = true;
                        
                        // For a full refresh of the UI after language change, consider refreshing the window
                        // or implementing a more sophisticated notification system
                    }
                }
            };
            
            // Show the settings window
            var window = GetMainWindow();
            if (window != null)
            {
                var settingsView = new Views.SettingsWindow();
                settingsView.DataContext = settingsWindow;
                settingsView.SetMainWindowViewModel(this); // Pass the main window view model
                settingsView.ShowDialog(window);
                
                // Save settings if they were changed
                if (settingsChanged)
                {
                    var settings = new ApplicationSettings
                    {
                        FontSize = FontSize,
                        FontFamily = FontFamily.Name,
                        ShowLineNumbers = ShowLineNumbers,
                        WordWrap = WordWrap,
                        LineHeight = LineHeight,
                        Language = _localizationService.GetCurrentCulture().Name
                    };
                    _settingsService.SaveSettings(settings);
                }
            }
        }
        
        /// <summary>
        /// Callback method called when the FontSize property changes. Updates the font size in all log lines and search results.
        /// </summary>
        /// <param name="value">The new font size value</param>
        partial void OnFontSizeChanged(double value)
        {
            // When font size changes, update the local FontSize property in all log lines and search results
            foreach (var logLine in LogLines)
            {
                if (logLine != null) {
                    logLine.FontSize = value;
                }
            }
            foreach (var result in SearchResults)
            {
                if (result != null) {
                    result.FontSize = value;
                }
            }
            
            // Save the updated setting
            var settings = _settingsService.LoadSettings();
            settings.FontSize = value;
            _settingsService.SaveSettings(settings);
        }
        
        /// <summary>
        /// Callback method called when the FontFamily property changes. Updates the font family in all log lines and search results.
        /// </summary>
        /// <param name="value">The new font family value</param>
        partial void OnFontFamilyChanged(FontFamily value)
        {
            // When font family changes, update the local FontFamily property in all log lines and search results
            foreach (var logLine in LogLines)
            {
                if (logLine != null) {
                    logLine.FontFamily = value ?? new FontFamily("Consolas");
                }
            }
            foreach (var result in SearchResults)
            {
                if (result != null) {
                    result.FontFamily = value ?? new FontFamily("Consolas");
                }
            }
            
            // Save the updated setting
            var settings = _settingsService.LoadSettings();
            settings.FontFamily = value?.Name ?? "Consolas";
            _settingsService.SaveSettings(settings);
        }
        
        /// <summary>
        /// Callback method called when the ShowLineNumbers property changes. Updates the line number visibility in all log lines.
        /// </summary>
        /// <param name="value">The new show line numbers value</param>
        partial void OnShowLineNumbersChanged(bool value)
        {
            // When line number visibility changes, update the local ShowLineNumbers property in all log lines
            foreach (var logLine in LogLines)
            {
                if (logLine != null) {
                    logLine.ShowLineNumbers = value;
                }
            }
            
            // Save the updated setting
            var settings = _settingsService.LoadSettings();
            settings.ShowLineNumbers = value;
            _settingsService.SaveSettings(settings);
        }
        
        /// <summary>
        /// Callback method called when the WordWrap property changes. 
        /// </summary>
        /// <param name="value">The new word wrap value</param>
        partial void OnWordWrapChanged(bool value)
        {
            // Update the text wrapping property
            ContentTextWrapping = value ? TextWrapping.Wrap : TextWrapping.NoWrap;
            
            // Save the updated setting
            var settings = _settingsService.LoadSettings();
            settings.WordWrap = value;
            _settingsService.SaveSettings(settings);
        }
        
        /// <summary>
        /// Callback method called when the LineHeight property changes. Updates the line height in all log lines and search results.
        /// </summary>
        /// <param name="value">The new line height value</param>
        partial void OnLineHeightChanged(double value)
        {
            // When line height changes, update the local LineHeight property in all log lines and search results
            foreach (var logLine in LogLines)
            {
                if (logLine != null) {
                    logLine.LineHeight = value;
                }
            }
            foreach (var result in SearchResults)
            {
                if (result != null) {
                    result.LineHeight = value;
                }
            }
            
            // Save the updated setting
            var settings = _settingsService.LoadSettings();
            settings.LineHeight = value;
            _settingsService.SaveSettings(settings);
        }
        
        /// <summary>
        /// Callback method called when the TotalLinesCount property changes. Updates the formatted line numbers in all log lines and search results.
        /// </summary>
        /// <param name="value">The new total lines count value</param>
        partial void OnTotalLinesCountChanged(int value)
        {
            // When total line count changes, update the formatted line numbers in all log lines and search results
            foreach (var logLine in LogLines)
            {
                if (logLine != null) {
                    logLine.TriggerFormattedLineNumberUpdate();
                }
            }
            
            foreach (var result in SearchResults)
            {
                if (result != null) {
                    result.TriggerFormattedLineNumberUpdate();
                }
            }
        }
        
        /// <summary>
        /// Navigates to a specific line in the log by highlighting it in the UI.
        /// </summary>
        /// <param name="lineNumber">The 1-based line number to navigate to</param>
        public void NavigateToLine(int lineNumber)
        {
            // For the entire file view, just highlight the specific line
            // Clear previous highlights
            foreach (var line in LogLines)
            {
                line.IsHighlighted = false;
            }
            
            // Find and highlight the target line (adjusting for 0-based indexing)
            int lineIndex = lineNumber - 1;
            if (lineIndex >= 0 && lineIndex < LogLines.Count)
            {
                LogLines[lineIndex].IsHighlighted = true;
                
                // Notify the view to scroll to the specific line
                // We'll handle the scrolling in the View by observing this property
                OnPropertyChanged(nameof(CurrentNavigationLineNumber));
            }
        }
        
        /// <summary>
        /// Gets or sets the line number for current navigation, used to trigger scrolling in the view.
        /// </summary>
        public int CurrentNavigationLineNumber { get; private set; } = 0;
        
        /// <summary>
        /// Updates the navigation line number and triggers scrolling to that line.
        /// </summary>
        /// <param name="lineNumber">The 1-based line number to navigate to</param>
        public void NavigateToLineWithScroll(int lineNumber)
        {
            // Update the navigation line number to trigger scrolling
            CurrentNavigationLineNumber = lineNumber;
            OnPropertyChanged(nameof(CurrentNavigationLineNumber));
            
            // Also highlight the line
            NavigateToLine(lineNumber);
        }
        
        /// <summary>
        /// Retrieves the main window instance from the application lifetime.
        /// </summary>
        /// <returns>The main window instance, or null if not available</returns>
        public Window GetMainWindow()
        {
            return (Avalonia.Application.Current.ApplicationLifetime as Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime)?.MainWindow;
        }
    
    /// <summary>
    /// Updates the application language and saves the setting.
    /// </summary>
    /// <param name="cultureName">The culture name to set (e.g., "en-US", "zh-Hans")</param>
    public void UpdateLanguage(string cultureName)
    {
        _localizationService.SetLanguage(cultureName);
        
        // Update the static localization manager as well to keep it in sync
        LocalizationManager.SetLanguage(cultureName);
        
        // Save the new language setting
        var settings = _settingsService.LoadSettings();
        settings.Language = cultureName;
        _settingsService.SaveSettings(settings);
    }
    
    /// <summary>
    /// Handles language change events by triggering UI updates
    /// </summary>
    /// <param name="culture">The new culture</param>
    private void OnLanguageChanged(CultureInfo culture)
    {
        // Trigger property changes to refresh the UI
        OnPropertyChanged(nameof(AppName));
    }
    
    /// <summary>
    /// Gets the application name based on current localization
    /// </summary>
    public string AppName => _localizationService.GetString("AppName");

    /// <summary>
    /// Gets the formatted status bar line count text based on current localization
    /// </summary>
    public string StatusBarLinesText => string.Format(_localizationService.GetString("StatusBarLines"), TotalLinesCount);
    
    /// <summary>
    /// Gets the localization service instance for use by markup extensions.
    /// </summary>
    /// <returns>The localization service instance</returns>
    public Services.LocalizationService GetLocalizationService()
    {
        return _localizationService;
    }
    
    /// <summary>
    /// Clears all color marks from both LogLines and SearchResults
    /// </summary>
    public void ClearAllColorMarks()
    {
        foreach (var logLine in LogLines)
        {
            logLine.ColorMark = string.Empty;
        }
        
        foreach (var searchResult in SearchResults)
        {
            searchResult.ColorMark = string.Empty;
        }
    }
}
    
    public partial class LogLineViewModel : ObservableObject
    {
        private int _lineNumber;
        public int LineNumber 
        { 
            get => _lineNumber; 
            set 
            { 
                if (SetProperty(ref _lineNumber, value))
                {
                    OnPropertyChanged(nameof(FormattedLineNumber));
                }
            } 
        }
        
        public string FormattedLineNumber
        {
            get 
            {
                // Calculate the number of digits needed based on max line number
                int digits = (ViewModel?.TotalLinesCount ?? 100).ToString().Length;
                // Format the line number with leading zeros
                string formattedNumber = _lineNumber.ToString().PadLeft(digits, '0');
                return $"{formattedNumber} | "; 
            }
        }
        
        public void TriggerFormattedLineNumberUpdate()
        {
            OnPropertyChanged(nameof(FormattedLineNumber));
        }
        
        [ObservableProperty]
        private string _content = string.Empty;
        
        [ObservableProperty]
        private bool _isHighlighted = false;
        
        [ObservableProperty]
        private string _colorMark = string.Empty;
        
        private MainWindowViewModel _viewModel;
        public MainWindowViewModel ViewModel 
        { 
            get => _viewModel;
            set
            {
                if (_viewModel != null)
                {
                    // Unsubscribe from previous ViewModel
                    _viewModel.PropertyChanged -= ViewModel_PropertyChanged;
                }
                
                _viewModel = value;
                
                if (_viewModel != null)
                {
                    // Subscribe to new ViewModel
                    _viewModel.PropertyChanged += ViewModel_PropertyChanged;
                    
                    // Immediately update with current ViewModel settings
                    FontSize = _viewModel.FontSize;
                    FontFamily = _viewModel.FontFamily;
                    ShowLineNumbers = _viewModel.ShowLineNumbers;
                    ContentTextWrapping = _viewModel.ContentTextWrapping;
                    LineHeight = _viewModel.LineHeight;
                }
                
                // Trigger update to formatted line number when ViewModel is set
                // Using RaisePropertyChanged to trigger property change notification
                OnPropertyChanged(nameof(FormattedLineNumber));
            }
        }
        
        private double _fontSize = 12.0;
        public double FontSize 
        { 
            get => _fontSize; 
            set => SetProperty(ref _fontSize, value); 
        }
        
        private FontFamily _fontFamily = new FontFamily("Consolas");
        public FontFamily FontFamily 
        { 
            get => _fontFamily; 
            set => SetProperty(ref _fontFamily, value); 
        }
        
        private bool _showLineNumbers = true;
        public bool ShowLineNumbers 
        { 
            get => _showLineNumbers; 
            set => SetProperty(ref _showLineNumbers, value); 
        }
        
        private TextWrapping _contentTextWrapping = TextWrapping.NoWrap;
        public TextWrapping ContentTextWrapping 
        { 
            get => _contentTextWrapping; 
            set => SetProperty(ref _contentTextWrapping, value); 
        }
        
        private double _lineHeight = 1.2;
        public double LineHeight 
        { 
            get => _lineHeight; 
            set => SetProperty(ref _lineHeight, value); 
        }
        
        /// <summary>
        /// Handles property changes in the parent view model, updating local properties accordingly.
        /// </summary>
        /// <param name="sender">The object that raised the event</param>
        /// <param name="e">Property changed event arguments</param>
        private void ViewModel_PropertyChanged(object sender, System.ComponentModel.PropertyChangedEventArgs e)
        {
            if (e.PropertyName == nameof(MainWindowViewModel.FontSize))
            {
                var newFontSize = ViewModel?.FontSize ?? 12.0;
                if (FontSize != newFontSize)
                {
                    FontSize = newFontSize;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.FontFamily))
            {
                var newFontFamily = ViewModel?.FontFamily ?? new FontFamily("Consolas");
                if (FontFamily != newFontFamily)
                {
                    FontFamily = newFontFamily;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.ShowLineNumbers))
            {
                var newShowLineNumbers = ViewModel?.ShowLineNumbers ?? true;
                if (ShowLineNumbers != newShowLineNumbers)
                {
                    ShowLineNumbers = newShowLineNumbers;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.ContentTextWrapping))
            {
                var newTextWrapping = ViewModel?.ContentTextWrapping ?? TextWrapping.NoWrap;
                if (ContentTextWrapping != newTextWrapping)
                {
                    ContentTextWrapping = newTextWrapping;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.LineHeight))
            {
                var newLineHeight = ViewModel?.LineHeight ?? 1.2;
                if (LineHeight != newLineHeight)
                {
                    LineHeight = newLineHeight;
                }
            }
        }
        
        /// <summary>
        /// Initializes a new instance of the LogLineViewModel class with default values.
        /// </summary>
        public LogLineViewModel()
        {
            // Set initial values - these will be overridden when ViewModel property is set
            FontSize = 12.0;
            FontFamily = new FontFamily("Consolas");
            ShowLineNumbers = true;
            LineHeight = 1.2;
        }
        
        [RelayCommand]
        public async Task MarkLineColorAsync(string color)
        {
            if (ViewModel != null)
            {
                ColorMark = color; // Set the color mark for this line
                // Also update the corresponding line in SearchResults if it exists
                var searchResult = ViewModel.SearchResults.FirstOrDefault(r => r.LineNumber == LineNumber);
                if (searchResult != null)
                {
                    searchResult.ColorMark = color;
                }
            }
        }
        
        /// <summary>
        /// Copies the content of this log line to the clipboard.
        /// </summary>
        [RelayCommand]
        public async Task CopyLineAsync()
        {
            if (ViewModel != null)
            {
                var window = ViewModel.GetMainWindow();
                if (window != null)
                {
                    await window.Clipboard.SetTextAsync(Content);
                }
            }
        }
    }
    
    public partial class SearchResultViewModel : ObservableObject
    {
        private int _lineNumber;
        public int LineNumber 
        { 
            get => _lineNumber; 
            set 
            { 
                if (SetProperty(ref _lineNumber, value))
                {
                    OnPropertyChanged(nameof(FormattedLineNumber));
                }
            } 
        }
        
        public string FormattedLineNumber
        {
            get 
            {
                // Calculate the number of digits needed based on max line number
                int digits = (ViewModel?.TotalLinesCount ?? 100).ToString().Length;
                // Format the line number with leading zeros
                string formattedNumber = _lineNumber.ToString().PadLeft(digits, '0');
                return $"{formattedNumber}"; 
            }
        }
        
        public void TriggerFormattedLineNumberUpdate()
        {
            OnPropertyChanged(nameof(FormattedLineNumber));
        }
        
        private string _content = string.Empty;
        public string Content 
        { 
            get => _content; 
            set => SetProperty(ref _content, value); 
        }
        
        private bool _showLineNumbers = true;
        public bool ShowLineNumbers 
        { 
            get => _showLineNumbers; 
            set => SetProperty(ref _showLineNumbers, value); 
        }
        
        private MainWindowViewModel _viewModel;
        public MainWindowViewModel ViewModel 
        { 
            get => _viewModel;
            set
            {
                if (_viewModel != null)
                {
                    // Unsubscribe from previous ViewModel
                    _viewModel.PropertyChanged -= ViewModel_PropertyChanged;
                }
                
                _viewModel = value;
                
                if (_viewModel != null)
                {
                    // Subscribe to new ViewModel
                    _viewModel.PropertyChanged += ViewModel_PropertyChanged;
                    
                    // Immediately update with current ViewModel settings
                    FontSize = _viewModel.FontSize;
                    FontFamily = _viewModel.FontFamily;
                    ShowLineNumbers = _viewModel.ShowLineNumbers;
                    ContentTextWrapping = _viewModel.ContentTextWrapping;
                    LineHeight = _viewModel.LineHeight;
                }
                
                // Trigger update to formatted line number when ViewModel is set
                OnPropertyChanged(nameof(FormattedLineNumber));
            }
        }
        
        private double _fontSize = 12.0;
        public double FontSize 
        { 
            get => _fontSize; 
            set => SetProperty(ref _fontSize, value); 
        }
        
        private FontFamily _fontFamily = new FontFamily("Consolas");
        public FontFamily FontFamily 
        { 
            get => _fontFamily; 
            set => SetProperty(ref _fontFamily, value); 
        }
        
        private TextWrapping _contentTextWrapping = TextWrapping.NoWrap;
        public TextWrapping ContentTextWrapping 
        { 
            get => _contentTextWrapping; 
            set => SetProperty(ref _contentTextWrapping, value); 
        }
        
        
        private string _colorMark = string.Empty;
        public string ColorMark
        { 
            get => _colorMark; 
            set => SetProperty(ref _colorMark, value); 
        }
        
        private double _lineHeight = 1.2;
        public double LineHeight 
        { 
            get => _lineHeight; 
            set => SetProperty(ref _lineHeight, value); 
        }
        
        /// <summary>
        /// Handles property changes in the parent view model, updating local properties accordingly.
        /// </summary>
        /// <param name="sender">The object that raised the event</param>
        /// <param name="e">Property changed event arguments</param>
        private void ViewModel_PropertyChanged(object sender, System.ComponentModel.PropertyChangedEventArgs e)
        {
            if (e.PropertyName == nameof(MainWindowViewModel.FontSize))
            {
                var newFontSize = ViewModel?.FontSize ?? 12.0;
                if (FontSize != newFontSize)
                {
                    FontSize = newFontSize;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.FontFamily))
            {
                var newFontFamily = ViewModel?.FontFamily ?? new FontFamily("Consolas");
                if (FontFamily != newFontFamily)
                {
                    FontFamily = newFontFamily;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.ShowLineNumbers))
            {
                var newShowLineNumbers = ViewModel?.ShowLineNumbers ?? true;
                if (ShowLineNumbers != newShowLineNumbers)
                {
                    ShowLineNumbers = newShowLineNumbers;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.ContentTextWrapping))
            {
                var newTextWrapping = ViewModel?.ContentTextWrapping ?? TextWrapping.NoWrap;
                if (ContentTextWrapping != newTextWrapping)
                {
                    ContentTextWrapping = newTextWrapping;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.LineHeight))
            {
                var newLineHeight = ViewModel?.LineHeight ?? 1.2;
                if (LineHeight != newLineHeight)
                {
                    LineHeight = newLineHeight;
                }
            }
            else if (e.PropertyName == nameof(MainWindowViewModel.TotalLinesCount))
            {
                // Update formatted line numbers when total line count changes
                OnPropertyChanged(nameof(FormattedLineNumber));
            }
        }
        
        [RelayCommand]
        public async Task MarkLineColorAsync(string color)
        {
            if (ViewModel != null)
            {
                ColorMark = color; // Set the color mark for this line
                // Also update the corresponding line in LogLines if it exists
                var logLine = ViewModel.LogLines.FirstOrDefault(l => l.LineNumber == LineNumber);
                if (logLine != null)
                {
                    logLine.ColorMark = color;
                }
            }
        }
        
        /// <summary>
        /// Initializes a new instance of the SearchResultViewModel class with default values.
        /// </summary>
        public SearchResultViewModel()
        {
            // Set initial values
            FontSize = 12.0;
            FontFamily = new FontFamily("Consolas");
            LineHeight = 1.2;
        }
        
        /// <summary>
        /// Copies the content of this search result to the clipboard.
        /// </summary>
        [RelayCommand]
        public async Task CopyLineAsync()
        {
            if (ViewModel != null)
            {
                var window = ViewModel.GetMainWindow();
                if (window != null)
                {
                    await window.Clipboard.SetTextAsync(Content);
                }
            }
        }
    }
}
