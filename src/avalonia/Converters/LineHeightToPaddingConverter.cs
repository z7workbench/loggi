using System;
using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Data.Converters;

namespace top.z7workbench.loggi.Converters
{
    public class LineHeightToPaddingConverter : IValueConverter
    {
        public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        {
            // Handle when value is null
            if (value == null)
            {
                return new Thickness(5, 5, 5, 5);
            }
            
            // Try to convert the value to a double
            double lineHeight;
            if (value is double d)
            {
                lineHeight = d;
            }
            else if (double.TryParse(value.ToString(), out double parsedValue))
            {
                lineHeight = parsedValue;
            }
            else
            {
                // Return default padding if conversion fails
                return new Thickness(5, 5, 5, 5);
            }

            // Calculate padding based on line height multiplier
            // The default line height is 1.2, so we adjust the calculation accordingly
            // For default value 1.2, we want default vertical padding of 5
            // So we use: (lineHeight / 1.2) * basePadding
            double basePadding = 5.0;
            double adjustedPadding = (lineHeight / 1.2) * basePadding;
            
            // Ensure a minimum padding to keep the text readable
            if (adjustedPadding < 1) adjustedPadding = 1;
            
            // Create a thickness with the calculated padding values
            // Using equal values for top/bottom to create more line spacing
            // Left and right padding stay constant at 5 to maintain consistent spacing
            return new Thickness(5, adjustedPadding, 5, adjustedPadding); // left, top, right, bottom
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException("ConvertBack is not implemented for LineHeightToPaddingConverter");
        }
    }
}