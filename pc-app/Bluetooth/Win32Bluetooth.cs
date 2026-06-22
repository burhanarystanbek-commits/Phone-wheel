using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PhoneWheelPC.Bluetooth;

/// <summary>
/// Minimal P/Invoke surface for Windows' native Bluetooth RFCOMM sockets
/// (AF_BTH / BTHPROTO_RFCOMM), used instead of a third-party NuGet package.
/// This avoids depending on an external library's exact published version
/// and build compatibility — everything here is plain Win32 Winsock that
/// ships with every Windows install, accessed through .NET's own
/// System.Net.Sockets.Socket class with a raw AddressFamily/ProtocolType
/// pair Windows recognizes even though .NET doesn't have first-class
/// constants for them.
///
/// Reference: https://learn.microsoft.com/windows/win32/bluetooth/bluetooth-and-bind
/// </summary>
internal static class Win32Bluetooth
{
    public const int AF_BTH = 32;
    public const int BTHPROTO_RFCOMM = 0x0003;

    [StructLayout(LayoutKind.Sequential)]
    public struct SOCKADDR_BTH
    {
        public short AddressFamily;     // AF_BTH
        public ulong btAddr;            // 8 bytes — Bluetooth device address (0 = any, for listen)
        public Guid serviceClassId;     // 16 bytes — RFCOMM service UUID
        public uint port;               // 4 bytes — RFCOMM channel, or 0 = BT_PORT_ANY for listen
    }

    /// <summary>Tries to create a raw AF_BTH/BTHPROTO_RFCOMM socket. If this
    /// fails with "address family not supported", there is no usable
    /// Bluetooth stack on this machine (no radio, drivers missing, etc).</summary>
    public static bool TryCreateRfcommSocket(out Socket? socket, out string? error)
    {
        try
        {
            socket = new Socket((AddressFamily)AF_BTH, SocketType.Stream, (ProtocolType)BTHPROTO_RFCOMM);
            error = null;
            return true;
        }
        catch (SocketException ex)
        {
            socket = null;
            error = ex.Message;
            return false;
        }
    }

    /// <summary>Builds the raw sockaddr bytes for binding a listening RFCOMM
    /// socket to the given service UUID on any local radio address.</summary>
    public static byte[] BuildListenSockAddr(Guid serviceUuid)
    {
        var addr = new SOCKADDR_BTH
        {
            AddressFamily = AF_BTH,
            btAddr = 0, // any local radio
            serviceClassId = serviceUuid,
            port = 0,   // BT_PORT_ANY — let Windows assign a channel
        };
        var size = Marshal.SizeOf<SOCKADDR_BTH>();
        var bytes = new byte[size];
        var ptr = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(addr, ptr, false);
            Marshal.Copy(ptr, bytes, 0, size);
        }
        finally
        {
            Marshal.FreeHGlobal(ptr);
        }
        return bytes;
    }
}

/// <summary>
/// EndPoint wrapper so System.Net.Sockets.Socket.Bind/Listen/Accept can be
/// used directly with the raw SOCKADDR_BTH structure, the same way
/// IPEndPoint works for AF_INET. .NET's Socket class only needs SocketAddress
/// bytes from an EndPoint — it doesn't need to understand the Bluetooth
/// address family itself.
/// </summary>
internal sealed class BluetoothListenEndPoint : EndPoint
{
    private readonly Guid _serviceUuid;

    public BluetoothListenEndPoint(Guid serviceUuid)
    {
        _serviceUuid = serviceUuid;
    }

    public override AddressFamily AddressFamily => (AddressFamily)Win32Bluetooth.AF_BTH;

    public override SocketAddress Serialize()
    {
        var bytes = Win32Bluetooth.BuildListenSockAddr(_serviceUuid);
        var sa = new SocketAddress(AddressFamily, bytes.Length);
        for (var i = 0; i < bytes.Length; i++) sa[i] = bytes[i];
        return sa;
    }

    public override EndPoint Create(SocketAddress socketAddress) => this;
}
