namespace AICalendar.Desktop.Tests;

/// <summary>Where the client may send the device token and the pairing code. Nothing here reaches the network.</summary>
public sealed class BridgeClientTests
{
    private static BridgeClient Client(string address) => new(new DesktopSettings { ServerUrl = address, DeviceToken = "test-device-token" });

    /// <summary>A hand-edited settings file with a plain http address: refused before any request is made.</summary>
    [Theory]
    [InlineData("http://calendar.example.com/api")]
    [InlineData("HTTP://calendar.example.com/api")]
    [InlineData("http://192.168.1.20:5001/api")]
    [InlineData("http://localhost.example.com/api")]
    [InlineData("ftp://calendar.example.com/api")]
    public async Task TheTokenIsNeverSentToAnAddressThatIsNotHttps(string address)
    {
        var client = Client(address);
        var refused = await Assert.ThrowsAsync<InsecureAddressException>(() => client.SyncAsync());
        Assert.Equal("The calendar service address must use https. Fix it under Tools > Cloud Connection.", refused.Message);
        await Assert.ThrowsAsync<InsecureAddressException>(() => client.GetKitchenAsync());
        await Assert.ThrowsAsync<InsecureAddressException>(() => client.AddCorrectionAsync(CorrectionKind.Note, "Tossed the parsley"));
        await Assert.ThrowsAsync<InsecureAddressException>(() => client.PairAsync("123456"));
    }

    /// <summary>https anywhere; plain http only to this PC itself, for a bridge run here.</summary>
    [Theory]
    [InlineData("https://calendar.example.com/api/", "https://calendar.example.com/api/v1/sync")]
    [InlineData(" https://calendar.example.com/api ", "https://calendar.example.com/api/v1/sync")]
    [InlineData("http://localhost:5001/api", "http://localhost:5001/api/v1/sync")]
    [InlineData("http://127.0.0.1:5001/api", "http://127.0.0.1:5001/api/v1/sync")]
    [InlineData("http://[::1]:5001/api", "http://[::1]:5001/api/v1/sync")]
    public void HttpsAndThisPcItselfAreAllowed(string address, string url) =>
        Assert.Equal(url, Client(address).Url("/v1/sync").ToString());
}
