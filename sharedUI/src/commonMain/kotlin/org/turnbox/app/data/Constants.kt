package org.turnbox.app.data

const val TUN2SOCKS_CONFIG_FILE_NAME = "tun2socks.yaml"
const val MASTER_HYSTERIA_CONFIG_FILE_NAME = "hysteria.yaml"
const val HYSTERIA_CONFIG_CACHE_FILE_NAME = "hysteria_cache.json"

const val TUN2SOCKS_CONFIG_TEXT_DATA = """
tunnel:
  name: tun0
  mtu: 1280
  multi-queue: false
  ipv4: 10.0.88.88
  ipv6: 'fc00::1'

socks5:
  port: 1080
  address: 127.0.0.1
  udp: 'udp'
"""