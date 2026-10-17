using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Microsoft.Extensions.DependencyInjection;
using SimpleNotes.Desktop.ViewModels;
using SimpleNotes.Desktop.Views;

namespace SimpleNotes.Desktop;

public partial class App : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        // DI 组合根：服务注册随功能设计逐步补充
        var services = new ServiceCollection();
        services.AddSingleton<MainWindowViewModel>();
        var provider = services.BuildServiceProvider();

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = new MainWindow
            {
                DataContext = provider.GetRequiredService<MainWindowViewModel>(),
            };
            desktop.Exit += (_, _) => provider.Dispose();
        }

        base.OnFrameworkInitializationCompleted();
    }
}
