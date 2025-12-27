using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Diagnostics;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Threading;
using Avalonia.VisualTree;
using System;
using top.z7workbench.loggi.Services;

namespace top.z7workbench.loggi.Views
{
    public partial class MainWindow : Window
    {
        private TextBlock _statusTextBlock;
        private TextBlock _filePathTextBlock;
        private TextBlock _lineCountTextBlock;
        private TextBlock _searchResultsCountTextBlock;
        private ContextMenu _logLineContextMenu;
        private ContextMenu _resultLineContextMenu;

        // Splitter dragging variables
        private bool _isSplitterDragging = false;
        private Point _lastMousePosition;

        /// <summary>
        /// Initializes a new instance of the MainWindow class, setting up the UI components and event connections.
        /// </summary>
        public MainWindow()
        {
            InitializeComponent();
#if DEBUG
            this.AttachDevTools();
#endif
            // Set the window icon
            try 
            {
                var iconPath = System.IO.Path.Combine(
                    System.AppContext.BaseDirectory, 
                    "Assets", 
                    "icon.svg"
                );
                if (System.IO.File.Exists(iconPath))
                {
                    this.Icon = new Avalonia.Controls.WindowIcon(iconPath);
                }
            }
            catch
            {
                // If loading the icon fails, continue without setting the icon
            }
            InitializeControls();
            ConnectEvents();
        }

        /// <summary>
        /// Initializes the Avalonia XAML components for the MainWindow.
        /// </summary>
        private void InitializeComponent()
        {
            AvaloniaXamlLoader.Load(this);
        }
        
        /// <summary>
        /// Finds and initializes all the UI controls in the MainWindow, connecting their event handlers.
        /// </summary>
        private void InitializeControls()
        {
            var searchTextBox = this.FindControl<TextBox>("SearchTextBox");
            if (searchTextBox != null)
            {
                searchTextBox.KeyDown += SearchTextBox_KeyDown;
            }
            
            // Get status bar elements
            _statusTextBlock = this.FindControl<TextBlock>("StatusTextBlock");
            _filePathTextBlock = this.FindControl<TextBlock>("FilePathTextBlock");
            _lineCountTextBlock = this.FindControl<TextBlock>("LineCountTextBlock");
            _searchResultsCountTextBlock = this.FindControl<TextBlock>("SearchResultsCount");
            
            // Connect menu items
            var openFileMenuItem = this.FindControl<MenuItem>("OpenFileMenuItem");
            if (openFileMenuItem != null)
            {
                openFileMenuItem.Click += OpenFileMenuItem_Click;
            }
            
            var exitMenuItem = this.FindControl<MenuItem>("ExitMenuItem");
            if (exitMenuItem != null)
            {
                exitMenuItem.Click += ExitMenuItem_Click;
            }
            
            var refreshMenuItem = this.FindControl<MenuItem>("RefreshMenuItem");
            if (refreshMenuItem != null)
            {
                refreshMenuItem.Click += RefreshMenuItem_Click;
            }
            
            var settingsMenuItem = this.FindControl<MenuItem>("SettingsMenuItem");
            if (settingsMenuItem != null)
            {
                settingsMenuItem.Click += SettingsMenuItem_Click;
            }
            
            var toggleThemeMenuItem = this.FindControl<MenuItem>("ToggleThemeMenuItem");
            if (toggleThemeMenuItem != null)
            {
                toggleThemeMenuItem.Click += ToggleThemeMenuItem_Click;
            }
            
            // Connect context menu items
            var copyLogLineMenuItem = this.FindControl<MenuItem>("CopyLogLineMenuItem");
            if (copyLogLineMenuItem != null)
            {
                copyLogLineMenuItem.Click += CopyLogLineMenuItem_Click;
            }
            
            var copyResultLineMenuItem = this.FindControl<MenuItem>("CopyResultLineMenuItem");
            if (copyResultLineMenuItem != null)
            {
                copyResultLineMenuItem.Click += CopyResultLineMenuItem_Click;
            }
        }
        
        /// <summary>
        /// Connects events that need to be handled by the MainWindow, such as data context changes.
        /// </summary>
        private void ConnectEvents()
        {
            // Update status when data context changes
            this.DataContextChanged += MainWindow_DataContextChanged;
        }
        
        /// <summary>
        /// Handles the event when the data context of the MainWindow changes, subscribing to view model property changes.
        /// </summary>
        /// <param name="sender">The object that raised the event</param>
        /// <param name="e">Event arguments</param>
        private void MainWindow_DataContextChanged(object sender, EventArgs e)
        {
            if (DataContext is ViewModels.MainWindowViewModel vm)
            {
                // Subscribe to property changes to update status bar
                vm.PropertyChanged += Vm_PropertyChanged;
                UpdateStatusBar(vm);
            }
        }

        /// <summary>
        /// Handles the event when a property in the view model changes, updating the status bar accordingly.
        /// </summary>
        /// <param name="sender">The object that raised the event</param>
        /// <param name="e">Property changed event arguments</param>
        private void Vm_PropertyChanged(object sender, System.ComponentModel.PropertyChangedEventArgs e)
        {
            if (DataContext is ViewModels.MainWindowViewModel vm)
            {
                UpdateStatusBar(vm);
            }
        }
        
        /// <summary>
        /// Handles the scroll changed event for the content scroll viewer to synchronize with line numbers.
        /// </summary>
        /// <param name="sender">The content scroll viewer that raised the event</param>
        /// <param name="e">ScrollChangedEventArgs containing scroll information</param>
        private void OnContentScrollChanged(object sender, Avalonia.Controls.ScrollChangedEventArgs e)
        {
            // Synchronize the line numbers scroll viewer with the content scroll viewer
            var lineNumbersScrollViewer = this.FindControl<ScrollViewer>("LineNumbersScrollViewer");
            var contentScrollViewer = sender as ScrollViewer;
            
            if (lineNumbersScrollViewer != null && contentScrollViewer != null)
            {
                // Use dispatcher to ensure update happens after layout
                Dispatcher.UIThread.Post(() => {
                    lineNumbersScrollViewer.Offset = new Vector(lineNumbersScrollViewer.Offset.X, contentScrollViewer.Offset.Y);
                }, DispatcherPriority.Render);
            }
        }
        
        /// <summary>
        /// Handles the scroll changed event for the search content scroll viewer to synchronize with search line numbers.
        /// </summary>
        /// <param name="sender">The search content scroll viewer that raised the event</param>
        /// <param name="e">ScrollChangedEventArgs containing scroll information</param>
        private void OnSearchContentScrollChanged(object sender, Avalonia.Controls.ScrollChangedEventArgs e)
        {
            // Synchronize the search line numbers scroll viewer with the search content scroll viewer
            var searchLineNumbersScrollViewer = this.FindControl<ScrollViewer>("SearchLineNumbersScrollViewer");
            var searchContentScrollViewer = sender as ScrollViewer;
            
            if (searchLineNumbersScrollViewer != null && searchContentScrollViewer != null)
            {
                // Use dispatcher to ensure update happens after layout
                Dispatcher.UIThread.Post(() => {
                    searchLineNumbersScrollViewer.Offset = new Vector(searchLineNumbersScrollViewer.Offset.X, searchContentScrollViewer.Offset.Y);
                }, DispatcherPriority.Render);
            }
        }

        /// <summary>
        /// Updates the status bar UI elements with information from the view model.
        /// </summary>
        /// <param name="vm">The MainWindowViewModel containing the status information</param>
        private void UpdateStatusBar(ViewModels.MainWindowViewModel vm)
        {
            if (_filePathTextBlock != null)
            {
                if (string.IsNullOrEmpty(vm.CurrentFilePath))
                {
                    _filePathTextBlock.Text = "No file loaded";
                }
                else
                {
                    // Get the absolute path and truncate if necessary to ensure filename is visible
                    string absolutePath = System.IO.Path.GetFullPath(vm.CurrentFilePath);
                    _filePathTextBlock.Text = TruncatePathForDisplay(absolutePath, 60); // Reasonable default length
                }
            }
            
            if (_lineCountTextBlock != null)
            {
                _lineCountTextBlock.Text = $"Lines: {vm.TotalLinesCount}";
            }
            
            if (_searchResultsCountTextBlock != null)
            {
                _searchResultsCountTextBlock.Text = $"Search Results: {vm.SearchResults.Count}";
            }
            
            if (_statusTextBlock != null)
            {
                _statusTextBlock.Text = vm.IsLoading ? "Loading..." : "Ready";
            }
        }

        /// <summary>
        /// Truncates a path to fit within a specified length by replacing the middle part with ellipsis
        /// while ensuring the filename is always visible.
        /// </summary>
        /// <param name="fullPath">The full path to truncate</param>
        /// <param name="maxLength">Maximum length of the resulting string</param>
        /// <returns>Truncated path with ellipsis in the middle</returns>
        private string TruncatePathForDisplay(string fullPath, int maxLength)
        {
            if (string.IsNullOrEmpty(fullPath) || fullPath.Length <= maxLength)
            {
                return fullPath;
            }

            string fileName = System.IO.Path.GetFileName(fullPath);
            string directoryPath = System.IO.Path.GetDirectoryName(fullPath);

            // If just the filename is too long, return truncated filename with ellipsis
            if (fileName.Length >= maxLength)
            {
                if (maxLength <= 3)
                {
                    return new string('.', maxLength);
                }
                return fileName.Substring(0, maxLength - 3) + "...";
            }

            // We need to truncate the directory path but keep the filename
            int availableLength = maxLength - fileName.Length - 3; // 3 for the ellipsis
            
            if (availableLength <= 0)
            {
                // If there's no space for directory, just show truncated filename
                return fileName.Length <= maxLength ? fileName : fileName.Substring(0, maxLength - 3) + "...";
            }

            // Take chars from the beginning of the directory path
            if (directoryPath.Length <= availableLength)
            {
                // No truncation needed for directory
                return fullPath;
            }

            // Truncate the directory path
            string truncatedDirectory = directoryPath.Substring(0, availableLength / 2) + 
                                       "..." + 
                                       directoryPath.Substring(directoryPath.Length - (availableLength - availableLength / 2), 
                                                               availableLength - availableLength / 2);
            
            return truncatedDirectory + System.IO.Path.DirectorySeparatorChar + fileName;
        }
        
        /// <summary>
        /// Handles the key down event in the search text box, triggering a search when Enter is pressed.
        /// </summary>
        /// <param name="sender">The search text box that raised the event</param>
        /// <param name="e">Key event arguments</param>
        private void SearchTextBox_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter)
            {
                if (DataContext is ViewModels.MainWindowViewModel vm)
                {
                    vm.SearchCommand.Execute(null);
                }
            }
        }
        
        /// <summary>
        /// Handles the double tap event on a search result item, navigating to the corresponding line in the log.
        /// </summary>
        /// <param name="sender">The UI element that was double-tapped</param>
        /// <param name="e">Tap event arguments</param>
        public void ResultItem_DoubleTapped(object sender, TappedEventArgs e)
        {
            if (sender is Border border && border.Tag is int lineNumber)
            {
                if (DataContext is ViewModels.MainWindowViewModel vm)
                {
                    vm.NavigateToLineWithScroll(lineNumber);
                    
                    // Additionally, scroll to the line in the main content view
                    ScrollToLineInMainView(lineNumber);
                }
            }
        }
        
        /// <summary>
        /// Scrolls the main log content to show the specified line number.
        /// </summary>
        /// <param name="lineNumber">The 1-based line number to scroll to</param>
        private void ScrollToLineInMainView(int lineNumber)
        {
            // Get the main content scroll viewer
            var logScrollViewer = this.FindControl<ScrollViewer>("LogScrollViewer");
            
            if (logScrollViewer != null)
            {
                // Calculate the approximate vertical offset to scroll to the line
                // This requires knowing the height of each line item, which can be complex
                // For now, we'll use a simpler approach by scrolling based on index
                
                // Calculate the index (0-based) of the target line
                int lineIndex = Math.Max(0, lineNumber - 1);
                
                // We'll set the scroll offset directly to approximately position the line
                // For now, just scroll to the calculated vertical position
                Dispatcher.UIThread.Post(() => {
                    // Calculate approximate offset based on line index and average line height
                    // This is an estimate - for precise scrolling, we would need to measure actual item positions
                    double lineHeight = 20; // Approximate height of each line
                    double targetOffset = lineIndex * lineHeight;
                    logScrollViewer.Offset = new Vector(logScrollViewer.Offset.X, targetOffset);
                }, DispatcherPriority.Render);
            }
        }
        
        /// <summary>
        /// Handles the click event on the Open File menu item, triggering the file open dialog.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void OpenFileMenuItem_Click(object sender, RoutedEventArgs e)
        {
            if (DataContext is ViewModels.MainWindowViewModel vm)
            {
                vm.OpenFileCommand.Execute(null);
            }
        }
        
        /// <summary>
        /// Handles the click event on the Exit menu item, shutting down the application.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void ExitMenuItem_Click(object sender, RoutedEventArgs e)
        {
            if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
            {
                desktop.Shutdown();
            }
        }
        
        /// <summary>
        /// Handles the click event on the Refresh menu item, reloading the currently open log file.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void RefreshMenuItem_Click(object sender, RoutedEventArgs e)
        {
            if (DataContext is ViewModels.MainWindowViewModel vm)
            {
                vm.RefreshCommand.Execute(null);
            }
        }
        
        /// <summary>
        /// Handles the click event on the Settings menu item, opening the settings window.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void SettingsMenuItem_Click(object sender, RoutedEventArgs e)
        {
            if (DataContext is ViewModels.MainWindowViewModel vm)
            {
                vm.SettingsCommand.Execute(null);
            }
        }
        
        /// <summary>
        /// Handles the click event on the Toggle Theme menu item, toggling between light and dark themes.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void ToggleThemeMenuItem_Click(object sender, RoutedEventArgs e)
        {
            Services.ThemeService.Instance.ToggleTheme();
        }
        
        /// <summary>
        /// Handles the click event on the Copy Log Line menu item, copying the selected log line to clipboard.
        /// This will be handled by the command in the ViewModel. The context menu is bound to the specific item's data context.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void CopyLogLineMenuItem_Click(object sender, RoutedEventArgs e)
        {
            // This will be handled by the command in the ViewModel
            // The context menu is bound to the specific item's data context
        }
        
        /// <summary>
        /// Handles the click event on the Copy Result Line menu item, copying the selected result line to clipboard.
        /// This will be handled by the command in the ViewModel. The context menu is bound to the specific item's data context.
        /// </summary>
        /// <param name="sender">The menu item that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void CopyResultLineMenuItem_Click(object sender, RoutedEventArgs e)
        {
            // This will be handled by the command in the ViewModel
            // The context menu is bound to the specific item's data context
        }

        // No need for the FindDescendantOfType method since we're using the named control directly

        /// <summary>
        /// Handles the PointerPressed event for the splitter, starting the drag operation.
        /// </summary>
        /// <param name="sender">The splitter border that was clicked</param>
        /// <param name="e">Pointer event arguments</param>
        private void Splitter_PointerPressed(object sender, Avalonia.Input.PointerPressedEventArgs e)
        {
            _isSplitterDragging = true;
            _lastMousePosition = e.GetPosition(this);
            e.Pointer.Capture(sender as IInputElement);
        }

        /// <summary>
        /// Handles the PointerMoved event for the splitter, updating the column widths during drag.
        /// </summary>
        /// <param name="sender">The splitter border that is being dragged</param>
        /// <param name="e">Pointer event arguments</param>
        private void Splitter_PointerMoved(object sender, Avalonia.Input.PointerEventArgs e)
        {
            if (!_isSplitterDragging) return;

            var currentMousePosition = e.GetPosition(this);
            var deltaX = currentMousePosition.X - _lastMousePosition.X;

            if (Math.Abs(deltaX) > 0)
            {
                // Get the named grid directly
                var grid = this.FindControl<Grid>("MainContentGrid");
                if (grid != null)
                {
                    var currentColumnDefinitions = grid.ColumnDefinitions;

                    // Get the current widths of the columns
                    var leftColumn = currentColumnDefinitions[0];
                    var rightColumn = currentColumnDefinitions[2];

                    // Calculate the total star value
                    var totalStarValue = leftColumn.Width.Value + rightColumn.Width.Value;

                    // Calculate the proportional change based on the grid's actual width
                    var gridWidth = grid.Bounds.Width - 6; // Subtract splitter width
                    if (gridWidth > 0)
                    {
                        // Calculate the change ratio based on the grid width
                        var changeRatio = deltaX / gridWidth;

                        // Calculate new star values
                        var newLeftStarValue = Math.Max(leftColumn.Width.Value + changeRatio * totalStarValue, 0.1); // Minimum 0.1 to prevent collapse
                        var newRightStarValue = Math.Max(rightColumn.Width.Value - changeRatio * totalStarValue, 0.1); // Minimum 0.1 to prevent collapse

                        // Update the column widths
                        leftColumn.Width = new GridLength(newLeftStarValue, GridUnitType.Star);
                        rightColumn.Width = new GridLength(newRightStarValue, GridUnitType.Star);
                    }
                }

                _lastMousePosition = currentMousePosition;
            }
        }

        /// <summary>
        /// Handles the PointerReleased event for the splitter, ending the drag operation.
        /// </summary>
        /// <param name="sender">The splitter border that was released</param>
        /// <param name="e">Pointer event arguments</param>
        private void Splitter_PointerReleased(object sender, Avalonia.Input.PointerReleasedEventArgs e)
        {
            _isSplitterDragging = false;
            e.Pointer.Capture(null);
        }
    }
}