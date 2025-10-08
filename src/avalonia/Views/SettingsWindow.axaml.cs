using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Avalonia.Interactivity;
using Avalonia.Styling;
using System.Linq;
using top.z7workbench.loggi.ViewModels;
using top.z7workbench.loggi.Services;

namespace top.z7workbench.loggi.Views
{
    public partial class SettingsWindow : Window
    {
        private Button _selectedCategoryButton;
        private MainWindowViewModel _mainWindowViewModel;

        /// <summary>
        /// Initializes a new instance of the SettingsWindow class, setting up UI components and event handlers.
        /// </summary>
        public SettingsWindow()
        {
            InitializeComponent();
#if DEBUG
            this.AttachDevTools();
#endif
            this.FindControl<Button>("SaveButton").Click += SaveButton_Click;
            this.FindControl<Button>("CancelButton").Click += CancelButton_Click;
            
            // Set initial selected button (the first one)
            _selectedCategoryButton = this.FindControl<Button>("FontSettingsButton");
            if (_selectedCategoryButton != null)
            {
                _selectedCategoryButton.Classes.Add("selected");
            }
        }
        
        /// <summary>
        /// Sets the main window view model to allow updating language settings.
        /// </summary>
        /// <param name="mainWindowViewModel">The main window view model</param>
        public void SetMainWindowViewModel(MainWindowViewModel mainWindowViewModel)
        {
            _mainWindowViewModel = mainWindowViewModel;
        }

        /// <summary>
        /// Initializes the Avalonia XAML components for the SettingsWindow.
        /// </summary>
        private void InitializeComponent()
        {
            AvaloniaXamlLoader.Load(this);
        }
        
        /// <summary>
        /// Handles the click event on the Save button, closing the settings window.
        /// </summary>
        /// <param name="sender">The Save button that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void SaveButton_Click(object sender, RoutedEventArgs e)
        {
            // If we have access to the main window view model, update the language
            if (_mainWindowViewModel != null && this.DataContext is SettingsWindowViewModel settingsViewModel)
            {
                _mainWindowViewModel.UpdateLanguage(settingsViewModel.SelectedLanguage);
            }
            
            this.Close();
        }
        
        /// <summary>
        /// Handles the click event on the Cancel button, closing the settings window.
        /// </summary>
        /// <param name="sender">The Cancel button that was clicked</param>
        /// <param name="e">Routed event arguments</param>
        private void CancelButton_Click(object sender, RoutedEventArgs e)
        {
            this.Close();
        }
        
        /// <summary>
        /// Handles the click event on category buttons to navigate within the settings window.
        /// </summary>
        /// <param name="sender">The clicked category button</param>
        /// <param name="e">Routed event arguments</param>
        private void CategoryButton_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button button)
            {
                // Reset previous selected button appearance
                if (_selectedCategoryButton != null)
                {
                    _selectedCategoryButton.Classes.Remove("selected");
                }
                
                // Update selected button
                _selectedCategoryButton = button;
                
                // Add selected class to button for visual indication
                _selectedCategoryButton.Classes.Add("selected");
                
                // Scroll to the corresponding section based on tag
                string tag = button.Tag?.ToString();
                if (!string.IsNullOrEmpty(tag))
                {
                    ScrollToSection(tag);
                }
            }
        }
        
        /// <summary>
        /// Scrolls the content to the specified section.
        /// </summary>
        /// <param name="section">The section to scroll to (e.g., FontSettings, ViewOptions)</param>
        private void ScrollToSection(string section)
        {
            var scrollViewer = this.FindControl<ScrollViewer>("ContentScrollViewer");
            if (scrollViewer == null) return;

            // Scroll to top first
            scrollViewer.Offset = new Vector(0, 0);
            
            // Find the corresponding textblock and scroll to it if possible
            var contentPanel = scrollViewer.Content as StackPanel;
            if (contentPanel != null)
            {
                foreach (var child in contentPanel.Children)
                {
                    if (child is TextBlock textBlock && textBlock.Text == section)
                    {
                        // Calculate the position of the text block
                        var controlPosition = textBlock.Bounds.Top;
                        scrollViewer.Offset = new Vector(0, controlPosition);
                        break;
                    }
                }
            }
        }
    }
}