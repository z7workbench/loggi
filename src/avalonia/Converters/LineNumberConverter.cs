using System;
using System.Globalization;
using Avalonia.Data.Converters;

namespace top.z7workbench.loggi.Converters
{
    public class LineNumberToStringConverter : IValueConverter
    {
        public static LineNumberToStringConverter Instance = new LineNumberToStringConverter();
        
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is int lineNumber)
            {
                // Convert the line number to a string with proper padding
                // The parameter can indicate the total number of lines to calculate padding width
                int totalLines = 100; // default to 3 digits (100-999)
                
                if (parameter is int maxLines)
                {
                    totalLines = maxLines;
                }
                
                // Calculate the number of digits needed based on max line number
                int digits = totalLines.ToString().Length;
                
                // Format the line number with leading zeros
                string formattedNumber = lineNumber.ToString().PadLeft(digits, '0');
                
                // Add the separator
                return $"{formattedNumber} | ";
            }
            
            return value?.ToString() ?? string.Empty;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
    
    public class LineNumberWithBracketsConverter : IValueConverter
    {
        public static LineNumberWithBracketsConverter Instance = new LineNumberWithBracketsConverter();
        
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is int lineNumber)
            {
                // Convert the line number to a string with proper padding for search results
                // The parameter can indicate the total number of lines to calculate padding width
                int totalLines = 100; // default to 3 digits (100-999)
                
                if (parameter is int maxLines)
                {
                    totalLines = maxLines;
                }
                
                // Calculate the number of digits needed based on max line number
                int digits = totalLines.ToString().Length;
                
                // Format the line number with leading zeros
                string formattedNumber = lineNumber.ToString().PadLeft(digits, '0');
                
                // Format as [Line XX]
                return $"[Line {formattedNumber}] ";
            }
            
            return value?.ToString() ?? string.Empty;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}