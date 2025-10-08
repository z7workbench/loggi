using System;
using System.Globalization;
using System.Resources;
using System.Reflection;

namespace top.z7workbench.loggi.Test
{
    class ResourceTest
    {
        static void Main(string[] args)
        {
            Console.WriteLine("Testing localization resources...");
            
            // Test resource manager with the correct resource path
            var assembly = Assembly.GetExecutingAssembly();
            var resourceManager = new ResourceManager("top.z7workbench.loggi.Resources.Strings", assembly);
            
            // Test English
            var cultureEn = CultureInfo.GetCultureInfo("en-US");
            Console.WriteLine("English - AppName: " + resourceManager.GetString("AppName", cultureEn));
            Console.WriteLine("English - MenuFile: " + resourceManager.GetString("MenuFile", cultureEn));
            
            // Test Chinese
            var cultureZh = CultureInfo.GetCultureInfo("zh-Hans");
            Console.WriteLine("Chinese - AppName: " + resourceManager.GetString("AppName", cultureZh));
            Console.WriteLine("Chinese - MenuFile: " + resourceManager.GetString("MenuFile", cultureZh));
            
            // Test that resources exist in assembly
            var resourceNames = assembly.GetManifestResourceNames();
            Console.WriteLine("\nAll embedded resources:");
            foreach (var name in resourceNames)
            {
                if (name.Contains("Strings"))
                    Console.WriteLine($" - {name}");
            }
            
            Console.WriteLine("Resource test completed.");
        }
    }
}