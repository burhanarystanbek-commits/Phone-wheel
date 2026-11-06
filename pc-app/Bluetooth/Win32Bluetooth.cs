using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PhoneWheelPC.Bluetooth;

internal static class Win32Bluetooth
{
    public const int    AF_BTH         = 32;
    public const int    BTHPROTO_RFCOMM = 0x0003;
    public const uint   BT_PORT_ANY    = 0;

    // ── SOCKADDR_BTH (ws2bth.h) ─────────────────────────────────────────────
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct SOCKADDR_BTH
    {
        public ushort addressFamily;   // AF_BTH = 32
        public ulong  btAddr;          // 0 = any local radio
        public Guid   serviceClassId;  // app UUID
        public uint   port;            // 0 = BT_PORT_ANY
    }

    // ── WSAQUERYSET / WSASetService for SDP registration ────────────────────
    // We only need the fields required to register an RFCOMM service record.
    // The full WSAQUERYSET is larger; we define only what WSASetService reads.

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WSAQUERYSET
    {
        public int    dwSize;               // sizeof(WSAQUERYSET)
        public IntPtr lpszServiceInstanceName; // service display name (LPWSTR)
        public IntPtr lpServiceClassId;     // pointer to service class GUID
        public IntPtr lpVersion;            // NULL
        public IntPtr lpszComment;          // NULL
        public int    dwNameSpace;          // NS_BTH = 16
        public IntPtr lpNSProviderId;       // NULL
        public IntPtr lpszContext;          // NULL
        public int    dwNumberOfProtocols;  // 0
        public IntPtr lpafpProtocols;       // NULL
        public IntPtr lpszQueryString;      // NULL
        public int    dwNumberOfCsAddrs;    // 1
        public IntPtr lpcsaBuffer;          // pointer to CSADDR_INFO
        public int    dwOutputFlags;        // 0
        public IntPtr lpBlob;               // NULL
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CSADDR_INFO
    {
        public SOCKET_ADDRESS LocalAddr;
        public SOCKET_ADDRESS RemoteAddr;
        public int            iSocketType;  // SOCK_STREAM
        public int            iProtocol;    // BTHPROTO_RFCOMM
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SOCKET_ADDRESS
    {
        public IntPtr lpSockaddr;
        public int    iSockaddrLength;
    }

    private const int NS_BTH         = 16;
    // WSAESETSERVICEOP: RNRSERVICE_REGISTER = 0, RNRSERVICE_DEREGISTER = 1.
    // (The old code passed 1, i.e. it *deregistered* — the SDP record never
    // existed, so Android's createRfcommSocketToServiceRecord could not
    // find the service and every connect failed.)
    private const int RNRSERVICE_REGISTER   = 0;
    private const int RNRSERVICE_DEREGISTER = 1;

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int bind(IntPtr s, ref SOCKADDR_BTH addr, int addrLen);

    [DllImport("ws2_32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int WSASetService(ref WSAQUERYSET lpqsRegInfo,
                                            int essoperation, int dwControlFlags);

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int getsockname(IntPtr s, ref SOCKADDR_BTH addr, ref int addrLen);

    // ── Public API ───────────────────────────────────────────────────────────

    public static bool TryCreateRfcommSocket(out Socket? socket, out string? error)
    {
        try
        {
            socket = new Socket((AddressFamily)AF_BTH, SocketType.Stream,
                                (ProtocolType)BTHPROTO_RFCOMM);
            error = null;
            return true;
        }
        catch (SocketException ex) { socket = null; error = ex.Message; return false; }
    }

    /// <summary>Binds the listening RFCOMM socket and registers the SDP
    /// service record so Android can discover it. Must be called after
    /// socket creation, before Listen().</summary>
    public static void BindAndRegisterSdp(Socket socket, Guid serviceUuid,
                                          string serviceName)
    {
        var handle = socket.Handle;

        // 1. Bind through the managed API with a custom EndPoint. A raw
        //    native bind() leaves .NET's Socket thinking it is unbound, and
        //    then Socket.AcceptAsync throws "You must call Bind first" —
        //    the server never accepted a single Bluetooth client.
        socket.Bind(new BluetoothEndPoint(0, Guid.Empty, BT_PORT_ANY));

        // 2. Read back the channel Windows assigned.
        var localAddr = new SOCKADDR_BTH();
        var localLen  = Marshal.SizeOf<SOCKADDR_BTH>();
        getsockname(handle, ref localAddr, ref localLen);
        // localAddr.port now holds the assigned RFCOMM channel number.

        // 3. Register SDP record via WSASetService so Android's SDP client
        //    can discover which channel our service is on.
        RegisterSdp(serviceUuid, serviceName, localAddr, RNRSERVICE_REGISTER);
        _registered = (serviceUuid, serviceName, localAddr);
    }

    private static (Guid uuid, string name, SOCKADDR_BTH addr)? _registered;

    /// <summary>Removes the SDP record registered by BindAndRegisterSdp so a
    /// restart doesn't leave stale records pointing at an old channel.</summary>
    public static void UnregisterSdp()
    {
        if (_registered is not { } r) return;
        _registered = null;
        try { RegisterSdp(r.uuid, r.name, r.addr, RNRSERVICE_DEREGISTER); } catch { }
    }

    private static unsafe void RegisterSdp(Guid serviceUuid, string serviceName,
                                            SOCKADDR_BTH localAddr, int op)
    {
        // Pin the strings and structs we need to pass as pointers.
        fixed (char* pName = serviceName)
        fixed (byte* pUuid = UuidToBytes(serviceUuid))
        {
            var csAddr = new CSADDR_INFO
            {
                iSocketType = (int)SocketType.Stream,
                iProtocol   = BTHPROTO_RFCOMM,
            };

            // LocalAddr points to our SOCKADDR_BTH.
            var sockAddrBytes = StructToBytes(localAddr);
            fixed (byte* pSockAddr = sockAddrBytes)
            {
                csAddr.LocalAddr = new SOCKET_ADDRESS
                {
                    lpSockaddr      = (IntPtr)pSockAddr,
                    iSockaddrLength = sockAddrBytes.Length,
                };
                csAddr.RemoteAddr = new SOCKET_ADDRESS { lpSockaddr = IntPtr.Zero };

                fixed (byte* pCsAddr = StructToBytes(csAddr))
                {
                    var qs = new WSAQUERYSET
                    {
                        dwSize               = Marshal.SizeOf<WSAQUERYSET>(),
                        lpszServiceInstanceName = (IntPtr)pName,
                        lpServiceClassId     = (IntPtr)pUuid,
                        dwNameSpace          = NS_BTH,
                        dwNumberOfCsAddrs    = 1,
                        lpcsaBuffer          = (IntPtr)pCsAddr,
                    };

                    var ret = WSASetService(ref qs, op, 0);
                    if (ret != 0)
                    {
                        var err = Marshal.GetLastWin32Error();
                        // Non-fatal: log but continue — basic connection still
                        // works on already-paired devices even without SDP.
                        System.Diagnostics.Debug.WriteLine(
                            $"WSASetService failed: {err} (non-fatal)");
                    }
                }
            }
        }
    }

    private static byte[] StructToBytes<T>(T s) where T : struct
    {
        var size = Marshal.SizeOf<T>();
        var buf  = new byte[size];
        var ptr  = Marshal.AllocHGlobal(size);
        try   { Marshal.StructureToPtr(s, ptr, false); Marshal.Copy(ptr, buf, 0, size); }
        finally { Marshal.FreeHGlobal(ptr); }
        return buf;
    }

    private static byte[] UuidToBytes(Guid g)
    {
        // Marshal Guid as WSAGUID (same layout as System.Guid on Windows).
        var size = Marshal.SizeOf<Guid>();
        var buf  = new byte[size];
        var ptr  = Marshal.AllocHGlobal(size);
        try   { Marshal.StructureToPtr(g, ptr, false); Marshal.Copy(ptr, buf, 0, size); }
        finally { Marshal.FreeHGlobal(ptr); }
        return buf;
    }
}

/// <summary>Managed EndPoint for AF_BTH (SOCKADDR_BTH, 30 bytes) so that
/// System.Net.Sockets can Bind/Accept Bluetooth RFCOMM sockets.</summary>
internal sealed class BluetoothEndPoint : System.Net.EndPoint
{
    private const int Size = 30;
    public ulong Address { get; }
    public Guid Service { get; }
    public uint Port { get; }

    public BluetoothEndPoint(ulong address, Guid service, uint port)
    { Address = address; Service = service; Port = port; }

    public override AddressFamily AddressFamily => (AddressFamily)Win32Bluetooth.AF_BTH;

    public override System.Net.SocketAddress Serialize()
    {
        var sa = new System.Net.SocketAddress(AddressFamily, Size);
        var addr = BitConverter.GetBytes(Address);
        for (var i = 0; i < 8; i++) sa[2 + i] = addr[i];
        var g = Service.ToByteArray();
        for (var i = 0; i < 16; i++) sa[10 + i] = g[i];
        var p = BitConverter.GetBytes(Port);
        for (var i = 0; i < 4; i++) sa[26 + i] = p[i];
        return sa;
    }

    public override System.Net.EndPoint Create(System.Net.SocketAddress sa)
    {
        var buf = new byte[Size];
        for (var i = 0; i < Math.Min(Size, sa.Size); i++) buf[i] = sa[i];
        return new BluetoothEndPoint(
            BitConverter.ToUInt64(buf, 2),
            new Guid(buf.AsSpan(10, 16)),
            BitConverter.ToUInt32(buf, 26));
    }

    public override string ToString() => $"BT {Address:X12} ch{Port}";
}
