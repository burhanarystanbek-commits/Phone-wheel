using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PhoneWheelPC.Bluetooth;

/// <summary>
/// Native Win32 Winsock Bluetooth (AF_BTH / BTHPROTO_RFCOMM) helpers.
///
/// We bypass Socket.Bind(EndPoint) entirely and call ws2_32!bind() directly
/// via P/Invoke. This is necessary because .NET's Socket.Bind() pipeline
/// (EndPoint.Serialize → SocketAddress → internal bind) mangles the
/// SOCKADDR_BTH bytes — it re-encodes the AddressFamily field in a way
/// Windows Bluetooth doesn't accept, producing WSAEADDRNOTAVAIL (10049).
///
/// Direct P/Invoke gives us full control: we marshal the struct ourselves,
/// pin it, and hand the raw pointer straight to Winsock — zero .NET
/// reinterpretation in between.
/// </summary>
internal static class Win32Bluetooth
{
    public const int AF_BTH        = 32;
    public const int BTHPROTO_RFCOMM = 0x0003;
    public const uint BT_PORT_ANY  = 0;

    // SOCKADDR_BTH as defined in ws2bth.h (Windows SDK).
    // Pack=1 gives exact wire layout: 2+8+16+4 = 30 bytes.
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct SOCKADDR_BTH
    {
        public ushort addressFamily;   // AF_BTH = 32
        public ulong  btAddr;          // 0 = any local radio
        public Guid   serviceClassId;  // our app's UUID
        public uint   port;            // 0 = BT_PORT_ANY
    }

    // Direct import of Winsock bind() — avoids .NET Socket.Bind() pipeline.
    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int bind(IntPtr s, ref SOCKADDR_BTH addr, int addrLen);

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int listen(IntPtr s, int backlog);

    /// <summary>Creates a raw AF_BTH/RFCOMM socket. Returns false when
    /// no Bluetooth radio or driver stack is present on this PC.</summary>
    public static bool TryCreateRfcommSocket(out Socket? socket, out string? error)
    {
        try
        {
            socket = new Socket(
                (AddressFamily)AF_BTH,
                SocketType.Stream,
                (ProtocolType)BTHPROTO_RFCOMM);
            error = null;
            return true;
        }
        catch (SocketException ex)
        {
            socket = null;
            error  = ex.Message;
            return false;
        }
    }

    /// <summary>Binds [socket] as a listening RFCOMM server for [serviceUuid]
    /// on any local Bluetooth radio. Calls ws2_32!bind() directly so the
    /// SOCKADDR_BTH bytes reach Winsock unmodified.</summary>
    public static void BindRfcommServer(Socket socket, Guid serviceUuid)
    {
        var addr = new SOCKADDR_BTH
        {
            addressFamily  = AF_BTH,
            btAddr         = 0,           // any local radio
            serviceClassId = serviceUuid,
            port           = BT_PORT_ANY, // Windows assigns an RFCOMM channel
        };

        var handle = socket.Handle; // SafeHandle → raw IntPtr
        var len    = Marshal.SizeOf<SOCKADDR_BTH>(); // 30

        var rc = bind(handle, ref addr, len);
        if (rc != 0)
        {
            var err = Marshal.GetLastWin32Error();
            throw new SocketException(err);
        }
    }
}
