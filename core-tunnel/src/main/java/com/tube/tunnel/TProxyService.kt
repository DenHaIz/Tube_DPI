package com.tube.tunnel

/**
 * JNI-мост к hev-socks5-tunnel. Имя класса и методов зашиты в hev-jni.c
 * (PKGNAME/CLSNAME в Application.mk), поэтому их нельзя переименовывать.
 */
@Suppress("FunctionName")
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Запускает туннель в отдельном нативном потоке; fd остаётся во владении вызывающего. */
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    /** [tx_packets, tx_bytes, rx_packets, rx_bytes] с момента запуска. */
    external fun TProxyGetStats(): LongArray
}
