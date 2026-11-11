package com.tube.tunnel

/**
 * Конфигурация hev-socks5-tunnel. DNS отвечает сам туннель (mapdns): выдаёт приложению
 * адрес из служебной сети и передаёт в SOCKS имя домена, а резолвит его уже byedpi —
 * вне VPN, системным резолвером. Так DNS работает, даже когда UDP через прокси запрещён.
 */
data class TunnelConfig(
    val socksPort: Int,
    val socksAddress: String = "127.0.0.1",
    val mtu: Int = MTU,
    val ipv4: String = TUN_IPV4,
    val ipv6: String = TUN_IPV6,
    val dnsAddress: String = MAPPED_DNS,
    // Не 100.64.0.0/10: это CGNAT, его выдают абонентам мобильные операторы.
    val mappedNetwork: String = "198.19.0.0",
    val mappedNetmask: String = "255.255.0.0",
) {
    fun toYaml(): String = """
        |tunnel:
        |  mtu: $mtu
        |  ipv4: $ipv4
        |  ipv6: '$ipv6'
        |
        |socks5:
        |  port: $socksPort
        |  address: $socksAddress
        |  udp: 'udp'
        |
        |mapdns:
        |  address: $dnsAddress
        |  port: 53
        |  network: $mappedNetwork
        |  netmask: $mappedNetmask
        |  cache-size: 10000
        |
        |misc:
        |  connect-timeout: 10000
        |  log-level: warn
        |""".trimMargin()

    companion object {
        const val MTU = 8500
        const val TUN_IPV4 = "198.18.0.1"
        const val TUN_IPV6 = "fc00::1"
        const val MAPPED_DNS = "198.18.0.2"
    }
}
