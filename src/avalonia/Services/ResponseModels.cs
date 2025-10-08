using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace top.z7workbench.loggi.Services
{
    /// <summary>
    /// Represents the response from a log line reading operation.
    /// </summary>
    public class LogLinesResponse
    {
        /// <summary>
        /// Gets or sets the list of log lines read from the file.
        /// </summary>
        [JsonPropertyName("lines")]
        public List<string> Lines { get; set; } = new List<string>();
        
        /// <summary>
        /// Gets or sets the total number of lines in the file.
        /// </summary>
        [JsonPropertyName("total_lines")]
        public int TotalLines { get; set; }
    }
    
    /// <summary>
    /// Represents a single search result containing line number and content.
    /// </summary>
    public class SearchResult
    {
        /// <summary>
        /// Gets or sets the line number where the match occurred.
        /// </summary>
        [JsonPropertyName("line_number")]
        public int LineNumber { get; set; }
        
        /// <summary>
        /// Gets or sets the content of the line that matched the search query.
        /// </summary>
        [JsonPropertyName("content")]
        public string Content { get; set; } = string.Empty;
    }
    
    /// <summary>
    /// Represents the response from a search operation containing all matches found.
    /// </summary>
    public class SearchResponse
    {
        /// <summary>
        /// Gets or sets the list of search results found in the file.
        /// </summary>
        [JsonPropertyName("matches")]
        public List<SearchResult> Matches { get; set; } = new List<SearchResult>();
    }
}