package hev.htproxy;

/**
 * JNI entry points of hev-socks5-tunnel. The package and class name are fixed
 * by the library: its JNI_OnLoad looks up exactly "hev/htproxy/TProxyService".
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    /** Starts the tunnel on its own native thread and returns immediately. */
    public static native void TProxyStartService(String configPath, int tunFd);

    public static native void TProxyStopService();

    /** {tx packets, tx bytes, rx packets, rx bytes} */
    public static native long[] TProxyGetStats();
}
