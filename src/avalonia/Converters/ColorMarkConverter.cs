using System;
using System.Globalization;
using Avalonia.Data.Converters;
using Avalonia.Media;

namespace top.z7workbench.loggi.Converters
{
    public class ColorMarkConverter : IValueConverter
    {
        /// <summary>
        /// Converts a color string value to a brush for marking. Returns the appropriate color brush based on the color string, or transparent if no color is specified.
        /// </summary>
        /// <param name="value">The color string value (e.g., "Red", "Blue", "Yellow", etc.)</param>
        /// <param name="targetType">The target type (not used)</param>
        /// <param name="parameter">Additional parameter (not used)</param>
        /// <param name="culture">The culture information (not used)</param>
        /// <returns>Color brush if value is specified, transparent brush otherwise</returns>
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            // Check for null value and return transparent brush
            if (value == null)
            {
                return Brushes.Transparent;
            }
            
            // Check if value is a string and not empty
            if (value is string colorString && !string.IsNullOrEmpty(colorString))
            {
                // Convert color string to brush
                return GetBrushFromColorString(colorString);
            }
            
            return Brushes.Transparent; // Default background when no color is set or value is null/empty
        }

        /// <summary>
        /// Converts back from brush to color string value. This method is not implemented as the converter is one-way.
        /// </summary>
        /// <param name="value">The brush value (not used)</param>
        /// <param name="targetType">The target type (not used)</param>
        /// <param name="parameter">Additional parameter (not used)</param>
        /// <param name="culture">The culture information (not used)</param>
        /// <returns>Not implemented - throws NotImplementedException</returns>
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
        
        /// <summary>
        /// Helper method to convert a color string to a brush
        /// </summary>
        /// <param name="colorString">The color string (e.g., "Red", "Blue", "Yellow", etc.)</param>
        /// <returns>The corresponding brush</returns>
        private static IBrush GetBrushFromColorString(string colorString)
        {
            return colorString.ToLower() switch
            {
                "red" => new SolidColorBrush(Color.Parse("#FFCDD2")), // Light red
                "orange" => new SolidColorBrush(Color.Parse("#FFE0B2")), // Light orange
                "yellow" => new SolidColorBrush(Color.Parse("#FFF9C4")), // Light yellow
                "green" => new SolidColorBrush(Color.Parse("#C8E6C9")), // Light green
                "cyan" => new SolidColorBrush(Color.Parse("#B2EBF2")), // Light cyan
                "blue" => new SolidColorBrush(Color.Parse("#BBDEFB")), // Light blue
                "purple" => new SolidColorBrush(Color.Parse("#E1BEE7")), // Light purple
                "gray" => new SolidColorBrush(Color.Parse("#E0E0E0")), // Light gray
                "magenta" => new SolidColorBrush(Color.Parse("#F8BBD0")), // Light magenta
                _ => Brushes.Transparent // Default to transparent if color is not recognized
            };
        }
    }
}