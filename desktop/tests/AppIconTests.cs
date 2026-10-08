using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace AICalendar.Desktop.Tests;

/// <summary>
/// Draws the app icon from the vector art in Xp/XpIcons.xaml and leaves AppIcon.ico (and a PNG to
/// look at) in tests/artifacts. After a change to the art, copy AppIcon.ico over the one in the
/// project folder; the build embeds it in the exe.
/// </summary>
[Collection(UiCollection.Name)]
public sealed class AppIconTests(UiFixture ui)
{
    /// <summary>Small sizes use the simple toolbar drawing, the rest the detailed one.</summary>
    private static readonly int[] Sizes = [16, 20, 24, 32, 40, 48, 64, 256];

    /// <summary>
    /// The toolbar drawing leaves a margin around the calendar that suits a button. As an icon of
    /// 16 to 24 pixels it is drawn larger, so the calendar fills the square.
    /// </summary>
    private const double SmallZoom = 1.16;

    [Fact]
    public void WritesTheAppIcon() => ui.Run(() =>
    {
        var small = (ImageSource)Application.Current.FindResource("Icon.App");
        var large = (ImageSource)Application.Current.FindResource("Icon.AppLarge");
        var frames = Sizes.Select(size => size < 32 ? Rasterize(small, size, SmallZoom) : Rasterize(large, size)).ToList();

        var path = Path.Combine(ui.Artifacts, "AppIcon.ico");
        File.WriteAllBytes(path, IconFile(frames));
        File.WriteAllBytes(Path.Combine(ui.Artifacts, "AppIcon.png"), Png(frames[^1]));

        using (var stream = File.OpenRead(path))
        {
            var decoder = new IconBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            Assert.Equal(Sizes.Order(), decoder.Frames.Select(frame => frame.PixelWidth).Order());
        }
        // The notification-area icon is loaded this way.
        using var icon = new System.Drawing.Icon(path, new System.Drawing.Size(16, 16));
        Assert.Equal(16, icon.Width);
    });

    private static BitmapSource Rasterize(ImageSource image, int size, double zoom = 1)
    {
        var visual = new DrawingVisual();
        var drawn = size * zoom;
        // Zoomed about the middle of the calendar, which sits a little above the middle of its grid.
        var area = new Rect((size - drawn) / 2, size / 2.0 - drawn * (11.75 / 24), drawn, drawn);
        using (var context = visual.RenderOpen()) context.DrawImage(image, zoom == 1 ? new Rect(0, 0, size, size) : area);
        var bitmap = new RenderTargetBitmap(size, size, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;
    }

    private static byte[] IconFile(IReadOnlyList<BitmapSource> frames)
    {
        // PNG is the usual encoding for the 256 pixel image; the smaller ones are plain bitmaps,
        // which every reader of .ico files understands.
        var images = frames.Select(frame => frame.PixelWidth >= 256 ? Png(frame) : Bitmap(frame)).ToList();
        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);
        writer.Write((ushort)0);
        writer.Write((ushort)1);
        writer.Write((ushort)frames.Count);
        var offset = 6 + 16 * frames.Count;
        for (var index = 0; index < frames.Count; index++)
        {
            var size = frames[index].PixelWidth;
            writer.Write((byte)(size >= 256 ? 0 : size));
            writer.Write((byte)(size >= 256 ? 0 : size));
            writer.Write((byte)0);
            writer.Write((byte)0);
            writer.Write((ushort)1);
            writer.Write((ushort)32);
            writer.Write(images[index].Length);
            writer.Write(offset);
            offset += images[index].Length;
        }
        foreach (var image in images) writer.Write(image);
        writer.Flush();
        return stream.ToArray();
    }

    private static byte[] Png(BitmapSource frame)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(frame));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    /// <summary>A 32-bit bitmap as .ico stores it: rows bottom-up, twice the height in the header, then the mask.</summary>
    private static byte[] Bitmap(BitmapSource frame)
    {
        var size = frame.PixelWidth;
        var stride = size * 4;
        var pixels = new byte[stride * size];
        new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0).CopyPixels(pixels, stride, 0);
        var maskStride = (size + 31) / 32 * 4;
        var mask = new byte[maskStride * size];
        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);
        writer.Write(40);
        writer.Write(size);
        writer.Write(size * 2);
        writer.Write((ushort)1);
        writer.Write((ushort)32);
        writer.Write(0);
        writer.Write(pixels.Length + mask.Length);
        writer.Write(0);
        writer.Write(0);
        writer.Write(0);
        writer.Write(0);
        for (var row = size - 1; row >= 0; row--)
        {
            writer.Write(pixels, row * stride, stride);
            for (var column = 0; column < size; column++)
            {
                // The mask marks fully transparent pixels, for readers that ignore the alpha channel.
                if (pixels[row * stride + column * 4 + 3] == 0) mask[(size - 1 - row) * maskStride + column / 8] |= (byte)(0x80 >> (column % 8));
            }
        }
        writer.Write(mask);
        writer.Flush();
        return stream.ToArray();
    }
}
