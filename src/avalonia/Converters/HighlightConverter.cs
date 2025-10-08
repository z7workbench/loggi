using System;
using System.Globalization;
using Avalonia.Data.Converters;
using Avalonia.Media;

namespace top.z7workbench.loggi.Converters
{
    public class HighlightConverter : IValueConverter
    {
        /// <summary>
        /// Converts a boolean value to a brush for highlighting. Returns yellow brush if the value is true, transparent otherwise.
        /// </summary>
        /// <param name="value">The boolean value indicating whether to highlight (true) or not (false)</param>
        /// <param name="targetType">The target type (not used)</param>
        /// <param name="parameter">Additional parameter (not used)</param>
        /// <param name="culture">The culture information (not used)</param>
        /// <returns>Yellow brush if value is true, transparent brush otherwise</returns>
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is bool isHighlighted && isHighlighted)
            {
                return Brushes.Yellow; // Highlight color for matched lines
            }
            return Brushes.Transparent; // Default background
        }

        /// <summary>
        /// Converts back from brush to boolean value. This method is not implemented as the converter is one-way.
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
    }
}