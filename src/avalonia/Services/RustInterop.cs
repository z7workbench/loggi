using System;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text;

namespace top.z7workbench.loggi.Services
{
    public class RustInterop : IDisposable
    {
        // Import FFI functions from Rust library
#if WINDOWS
        private const string DllName = "loggi_core.dll";
#elif LINUX
        private const string DllName = "libloggi_core.so";
#else
        private const string DllName = "libloggi_core.dylib"; // macOS and other Unix systems
#endif

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr read_log_lines(IntPtr file_path, ulong start_line, ulong line_count);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr search_log_lines(IntPtr file_path, IntPtr query, [MarshalAs(UnmanagedType.Bool)] bool case_sensitive);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr regex_search_log_lines(IntPtr file_path, IntPtr pattern, [MarshalAs(UnmanagedType.Bool)] bool case_sensitive);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        private static extern ulong get_total_line_count(IntPtr file_path);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        private static extern void free_string(IntPtr ptr);

        /// <summary>
        /// Reads a specified range of lines from a log file using the Rust library.
        /// </summary>
        /// <param name="filePath">The path to the log file to read from</param>
        /// <param name="startLine">The starting line number to read from (0-indexed)</param>
        /// <param name="lineCount">The number of lines to read</param>
        /// <returns>LogLinesResponse containing the requested lines and total line count</returns>
        public LogLinesResponse ReadLogLines(string filePath, int startLine, int lineCount)
        {
            var filePathPtr = Marshal.StringToHGlobalAnsi(filePath);
            var startLineUint = (ulong)startLine;
            var lineCountUint = (ulong)lineCount;

            try
            {
                var resultPtr = read_log_lines(filePathPtr, startLineUint, lineCountUint);
                
                if (resultPtr == IntPtr.Zero)
                {
                    throw new InvalidOperationException("Rust function returned null");
                }

                var jsonString = Marshal.PtrToStringAnsi(resultPtr);
                var response = JsonSerializer.Deserialize<LogLinesResponse>(jsonString) ?? new LogLinesResponse();

                // Free the string allocated by Rust
                free_string(resultPtr);
                
                return response ?? new LogLinesResponse();
            }
            finally
            {
                Marshal.FreeHGlobal(filePathPtr);
            }
        }

        /// <summary>
        /// Searches for a text pattern in a log file using the Rust library.
        /// </summary>
        /// <param name="filePath">The path to the log file to search in</param>
        /// <param name="query">The text pattern to search for</param>
        /// <param name="caseSensitive">Whether the search should be case sensitive (default: false)</param>
        /// <returns>SearchResponse containing the matches found in the file</returns>
        public SearchResponse SearchLogLines(string filePath, string query, bool caseSensitive = false)
        {
            System.Diagnostics.Debug.WriteLine($"RustInterop: Starting search for '{query}' in '{filePath}', case sensitive: {caseSensitive}");
            Console.WriteLine($"RustInterop: Starting search for '{query}' in '{filePath}', case sensitive: {caseSensitive}");
            
            var filePathPtr = Marshal.StringToHGlobalAnsi(filePath);
            var queryPtr = Marshal.StringToHGlobalAnsi(query);

            try
            {
                var resultPtr = search_log_lines(filePathPtr, queryPtr, caseSensitive);
                
                System.Diagnostics.Debug.WriteLine($"RustInterop: resultPtr = {resultPtr}, IntPtr.Zero = {IntPtr.Zero}");
                Console.WriteLine($"RustInterop: resultPtr = {resultPtr}, IntPtr.Zero = {IntPtr.Zero}");
                
                if (resultPtr == IntPtr.Zero)
                {
                    System.Diagnostics.Debug.WriteLine("RustInterop: search_log_lines returned null pointer");
                    Console.WriteLine("RustInterop: search_log_lines returned null pointer");
                    throw new InvalidOperationException("Rust function returned null");
                }

                var jsonString = Marshal.PtrToStringAnsi(resultPtr);
                System.Diagnostics.Debug.WriteLine($"RustInterop: Raw JSON response: {jsonString}");
                Console.WriteLine($"RustInterop: Raw JSON response: {jsonString}");
                
                var response = JsonSerializer.Deserialize<SearchResponse>(jsonString) ?? new SearchResponse();
                
                System.Diagnostics.Debug.WriteLine($"RustInterop: Deserialized response with {response?.Matches?.Count ?? -1} matches");
                Console.WriteLine($"RustInterop: Deserialized response with {response?.Matches?.Count ?? -1} matches");

                // Free the string allocated by Rust
                free_string(resultPtr);
                
                return response ?? new SearchResponse();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"RustInterop: SearchLogLines exception: {ex.Message}");
                Console.WriteLine($"RustInterop: SearchLogLines exception: {ex.Message}");
                System.Diagnostics.Debug.WriteLine($"RustInterop: SearchLogLines stack trace: {ex.StackTrace}");
                Console.WriteLine($"RustInterop: SearchLogLines stack trace: {ex.StackTrace}");
                throw;
            }
            finally
            {
                Marshal.FreeHGlobal(filePathPtr);
                Marshal.FreeHGlobal(queryPtr);
            }
        }

        /// <summary>
        /// Performs a regex search for a pattern in a log file using the Rust library.
        /// </summary>
        /// <param name="filePath">The path to the log file to search in</param>
        /// <param name="pattern">The regex pattern to search for</param>
        /// <param name="caseSensitive">Whether the search should be case sensitive (default: false)</param>
        /// <returns>SearchResponse containing the matches found in the file</returns>
        public SearchResponse RegexSearchLogLines(string filePath, string pattern, bool caseSensitive = false)
        {
            System.Diagnostics.Debug.WriteLine($"RustInterop: Starting regex search for pattern '{pattern}' in '{filePath}', case sensitive: {caseSensitive}");
            Console.WriteLine($"RustInterop: Starting regex search for pattern '{pattern}' in '{filePath}', case sensitive: {caseSensitive}");
            
            var filePathPtr = Marshal.StringToHGlobalAnsi(filePath);
            var patternPtr = Marshal.StringToHGlobalAnsi(pattern);

            try
            {
                var resultPtr = regex_search_log_lines(filePathPtr, patternPtr, caseSensitive);
                
                System.Diagnostics.Debug.WriteLine($"RustInterop: regex resultPtr = {resultPtr}, IntPtr.Zero = {IntPtr.Zero}");
                Console.WriteLine($"RustInterop: regex resultPtr = {resultPtr}, IntPtr.Zero = {IntPtr.Zero}");
                
                if (resultPtr == IntPtr.Zero)
                {
                    System.Diagnostics.Debug.WriteLine("RustInterop: regex_search_log_lines returned null pointer");
                    Console.WriteLine("RustInterop: regex_search_log_lines returned null pointer");
                    throw new InvalidOperationException("Rust function returned null");
                }

                var jsonString = Marshal.PtrToStringAnsi(resultPtr);
                System.Diagnostics.Debug.WriteLine($"RustInterop: Regex raw JSON response: {jsonString}");
                Console.WriteLine($"RustInterop: Regex raw JSON response: {jsonString}");
                
                var response = JsonSerializer.Deserialize<SearchResponse>(jsonString) ?? new SearchResponse();
                
                System.Diagnostics.Debug.WriteLine($"RustInterop: Regex deserialized response with {response?.Matches?.Count ?? -1} matches");
                Console.WriteLine($"RustInterop: Regex deserialized response with {response?.Matches?.Count ?? -1} matches");

                // Free the string allocated by Rust
                free_string(resultPtr);
                
                return response ?? new SearchResponse();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"RustInterop: RegexSearchLogLines exception: {ex.Message}");
                Console.WriteLine($"RustInterop: RegexSearchLogLines exception: {ex.Message}");
                System.Diagnostics.Debug.WriteLine($"RustInterop: RegexSearchLogLines stack trace: {ex.StackTrace}");
                Console.WriteLine($"RustInterop: RegexSearchLogLines stack trace: {ex.StackTrace}");
                throw;
            }
            finally
            {
                Marshal.FreeHGlobal(filePathPtr);
                Marshal.FreeHGlobal(patternPtr);
            }
        }

        /// <summary>
        /// Gets the total number of lines in a log file using the Rust library.
        /// </summary>
        /// <param name="filePath">The path to the log file to count lines for</param>
        /// <returns>The total number of lines in the file</returns>
        public int GetTotalLineCount(string filePath)
        {
            var filePathPtr = Marshal.StringToHGlobalAnsi(filePath);

            try
            {
                var result = get_total_line_count(filePathPtr);
                return (int)result;
            }
            finally
            {
                Marshal.FreeHGlobal(filePathPtr);
            }
        }

        /// <summary>
        /// Disposes of resources used by the RustInterop class.
        /// </summary>
        public void Dispose()
        {
            // No specific cleanup required for this class
        }
    }
}