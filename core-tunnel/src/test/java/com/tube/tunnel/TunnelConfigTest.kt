package com.tube.tunnel

import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun yamlContainsSocksAndMappedDns() {
        val yaml = TunnelConfig(socksPort = 1080).toYaml()
        assertTrue(yaml, yaml.contains("socks5:\n  port: 1080\n  address: 127.0.0.1"))
        assertTrue(yaml, yaml.contains("mapdns:\n  address: ${TunnelConfig.MAPPED_DNS}"))
        assertTrue(yaml, yaml.startsWith("tunnel:\n  mtu: ${TunnelConfig.MTU}"))
    }
}
