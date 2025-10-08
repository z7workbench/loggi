using System;
using Avalonia.Controls;
using Avalonia.Controls.Templates;
using top.z7workbench.loggi.ViewModels;

namespace top.z7workbench.loggi
{
    public class ViewLocator : IDataTemplate
    {
        /// <summary>
        /// Creates a view control instance based on the type of the provided data context.
        /// Converts the ViewModel type name to a corresponding View type name and instantiates it.
        /// </summary>
        /// <param name="data">The data context object (typically a ViewModel)</param>
        /// <returns>A control instance for the corresponding view, or a TextBlock with error message if not found</returns>
        public Control Build(object data)
        {
            var name = data.GetType().FullName!.Replace("ViewModel", "View");
            var type = Type.GetType(name);

            if (type != null)
            {
                return (Control)Activator.CreateInstance(type)!;
            }
            
            return new TextBlock { Text = "Not Found: " + name };
        }

        /// <summary>
        /// Determines if this ViewLocator can handle the provided data context.
        /// </summary>
        /// <param name="data">The data context object to check</param>
        /// <returns>True if the data is a ViewModelBase instance, false otherwise</returns>
        public bool Match(object data)
        {
            return data is ViewModelBase;
        }
    }
}