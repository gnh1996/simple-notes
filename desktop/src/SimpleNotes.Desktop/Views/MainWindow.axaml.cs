using Avalonia.Controls;
using Avalonia.Platform;

namespace SimpleNotes.Desktop.Views;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();

        // Windows 的窗口图标只接受 ICO；Linux / macOS 用透明 PNG。
        var iconFile = OperatingSystem.IsWindows() ? "icon.ico" : "icon.png";
        using var stream = AssetLoader.Open(new Uri($"avares://SimpleNotes.Desktop/Assets/{iconFile}"));
        Icon = new WindowIcon(stream);
    }
}
