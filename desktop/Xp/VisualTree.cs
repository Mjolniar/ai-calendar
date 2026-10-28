using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Media3D;

namespace AICalendar.Desktop.Xp;

/// <summary>Lookups in the element tree that the list handling needs.</summary>
internal static class VisualTree
{
    /// <summary>The parent of an element, also for text content that is not itself a visual.</summary>
    public static DependencyObject? Parent(DependencyObject element) =>
        element is Visual or Visual3D ? VisualTreeHelper.GetParent(element) : LogicalTreeHelper.GetParent(element);

    /// <summary>
    /// The nearest <typeparamref name="T"/> from <paramref name="element"/> upwards, the element
    /// itself included. The search stops at <paramref name="boundary"/>.
    /// </summary>
    public static T? Ancestor<T>(DependencyObject? element, DependencyObject? boundary = null) where T : DependencyObject
    {
        for (var current = element; current is not null && !ReferenceEquals(current, boundary); current = Parent(current))
        {
            if (current is T match) return match;
        }
        return null;
    }

    /// <summary>True when <paramref name="element"/> sits inside an element with this name, below <paramref name="boundary"/>.</summary>
    public static bool IsInsideNamed(DependencyObject? element, string name, DependencyObject boundary)
    {
        for (var current = element; current is not null && !ReferenceEquals(current, boundary); current = Parent(current))
        {
            if (current is FrameworkElement { Name: var elementName } && elementName == name) return true;
        }
        return false;
    }

    /// <summary>The first <typeparamref name="T"/> below <paramref name="element"/>, depth first.</summary>
    public static T? Descendant<T>(DependencyObject element) where T : DependencyObject
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
        {
            var child = VisualTreeHelper.GetChild(element, index);
            if (child is T match) return match;
            if (Descendant<T>(child) is { } nested) return nested;
        }
        return null;
    }
}
