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
    private const int RNRSERVICE_REGISTER = 1;

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

        // 1. Bind to any local radio, any channel (BT_PORT_ANY = 0).
        var bindAddr = new SOCKADDR_BTH
        {
            addressFamily  = AF_BTH,
            btAddr         = 0,
            serviceClassId = serviceUuid,
            port           = BT_PORT_ANY,
        };
        var bindLen = Marshal.SizeOf<SOCKADDR_BTH>(); // 30 bytes
        var rc = bind(handle, ref bindAddr, bindLen);
        if (rc != 0)
            throw new SocketException(Marshal.GetLastWin32Error());

        // 2. Read back the channel Windows assigned.
        var localAddr = new SOCKADDR_BTH();
        var localLen  = Marshal.SizeOf<SOCKADDR_BTH>();
        getsockname(handle, ref localAddr, ref localLen);
        // localAddr.port now holds the assigned RFCOMM channel number.

        // 3. Register SDP record via WSASetService so Android's SDP client
        //    can discover which channel our service is on.
        RegisterSdp(handle, serviceUuid, serviceName, localAddr);
    }

    private static unsafe void RegisterSdp(IntPtr socketHandle, Guid serviceUuid,
                                            string serviceName,
                                            SOCKADDR_BTH localAddr)
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

                    var ret = WSASetService(ref qs, RNRSERVICE_REGISTER, 0);
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
