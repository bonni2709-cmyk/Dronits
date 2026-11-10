#!/usr/bin/env python3
"""CI: exercise the real lwIP tun2socks with raw packets, not mocked UDP booleans.
A datagram socketpair stands in for an Android IFF_NO_PI TUN descriptor.
Only loopback echo servers started by the Go integration test are contacted.
"""
import ctypes
import select
import socket
import struct
import sys
import threading
import time


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    value = sum(struct.unpack('!%dH' % (len(data) // 2), data))
    while value >> 16:
        value = (value & 0xffff) + (value >> 16)
    return (~value) & 0xffff


def packet(version, protocol, source, destination, sport, dport, payload, seq=0, ack=0, flags=0):
    family = socket.AF_INET if version == 4 else socket.AF_INET6
    src, dst = socket.inet_pton(family, source), socket.inet_pton(family, destination)
    if protocol == 17:
        segment = struct.pack('!HHHH', sport, dport, 8 + len(payload), 0) + payload
    else:
        segment = struct.pack('!HHIIBBHHH', sport, dport, seq & 0xffffffff, ack & 0xffffffff,
                              5 << 4, flags, 65535, 0, 0) + payload
    pseudo = src + dst + (struct.pack('!BBH', 0, protocol, len(segment)) if version == 4 else
                          struct.pack('!I3xB', len(segment), protocol))
    offset = 6 if protocol == 17 else 16
    value = checksum(pseudo + segment) or 0xffff
    segment = segment[:offset] + struct.pack('!H', value) + segment[offset + 2:]
    if version == 4:
        header = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + len(segment), 1, 0, 64, protocol, 0, src, dst)
        header = header[:10] + struct.pack('!H', checksum(header)) + header[12:]
    else:
        header = struct.pack('!IHBB16s16s', 6 << 28, len(segment), protocol, 64, src, dst)
    return header + segment


def parse(data):
    version = data[0] >> 4
    if version == 4:
        size, protocol, src, dst = (data[0] & 15) * 4, data[9], data[12:16], data[16:20]
        assert checksum(data[:size]) == 0, 'invalid IPv4 header checksum'
    elif version == 6:
        size, protocol, src, dst = 40, data[6], data[8:24], data[24:40]
    else:
        return None
    if protocol not in (6, 17):
        return None
    segment = data[size:]
    pseudo = src + dst + (struct.pack('!BBH', 0, protocol, len(segment)) if version == 4 else
                          struct.pack('!I3xB', len(segment), protocol))
    assert checksum(pseudo + segment) == 0, 'invalid transport checksum'
    sport, dport = struct.unpack('!HH', segment[:4])
    if protocol == 17:
        return version, protocol, sport, dport, 0, 0, 0, segment[8:]
    seq, ack = struct.unpack('!II', segment[4:12])
    return version, protocol, sport, dport, seq, ack, segment[13], segment[(segment[12] >> 4) * 4:]


def receive(tun, predicate):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        readable, _, _ = select.select([tun], [], [], max(0, deadline - time.monotonic()))
        if not readable:
            break
        frame = parse(tun.recv(65535))
        if frame is not None and predicate(frame):
            return frame
    raise AssertionError('no matching return packet from tun2socks')


def udp(tun, version, source, destination, port, source_port):
    payload = b'blitz-udp\x00' + bytes(range(256)) * 4 + struct.pack('!H', port)
    tun.send(packet(version, 17, source, destination, source_port, port, payload))
    reply = receive(tun, lambda p: p[:4] == (version, 17, port, source_port))
    assert reply[7] == payload, 'UDP datagram boundaries or content changed'


def tcp(tun, version, source, destination, port, source_port):
    sequence = 100000 + source_port
    tun.send(packet(version, 6, source, destination, source_port, port, b'', sequence, 0, 0x02))
    syn = receive(tun, lambda p: p[:4] == (version, 6, port, source_port) and p[6] & 0x12 == 0x12)
    assert syn[5] == sequence + 1
    remote = (syn[4] + 1) & 0xffffffff
    sequence += 1
    tun.send(packet(version, 6, source, destination, source_port, port, b'', sequence, remote, 0x10))
    payload = b'blitz-tcp\x00' + struct.pack('!H', port)
    tun.send(packet(version, 6, source, destination, source_port, port, payload, sequence, remote, 0x18))
    sequence += len(payload)
    result = b''
    while len(result) < len(payload):
        reply = receive(tun, lambda p: p[:4] == (version, 6, port, source_port) and bool(p[7]))
        assert reply[4] == remote, 'TCP sequence mismatch'
        result += reply[7]
        remote = (remote + len(reply[7])) & 0xffffffff
        tun.send(packet(version, 6, source, destination, source_port, port, b'', sequence, remote, 0x10))
    assert result == payload, 'TCP payload changed'
    tun.send(packet(version, 6, source, destination, source_port, port, b'', sequence, remote, 0x11))


def main():
    library = ctypes.CDLL(sys.argv[1])
    library.hev_socks5_tunnel_main_from_str.argtypes = [ctypes.c_char_p, ctypes.c_uint, ctypes.c_int]
    library.hev_socks5_tunnel_main_from_str.restype = ctypes.c_int
    library.hev_socks5_tunnel_quit.argtypes = []
    port = int(sys.argv[2])
    config = f'''tunnel:
  mtu: 1400
  ipv4: '10.77.0.1'
  ipv6: 'fd42:776f:7400::1'
socks5:
  address: 127.0.0.1
  port: {port}
  udp: 'udp'
  udp-address: 127.0.0.1
  username: 'fixture-user'
  password: 'fixture-password'
misc:
  log-file: stderr
  log-level: error
'''.encode()
    # Check restart/fd ownership as well as both directions of the data plane.
    for attempt in range(2):
        app, native = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        result = []
        thread = threading.Thread(target=lambda: result.append(
            library.hev_socks5_tunnel_main_from_str(config, len(config), native.fileno())), daemon=True)
        thread.start()
        try:
            for version, src, dst in ((4, '10.77.0.1', '127.0.0.1'), (6, 'fd42:776f:7400::1', '::1')):
                for index, target in enumerate((53, 12000, 27000, 43000)):
                    udp(app, version, src, dst, target, 51000 + index)
                for index, target in enumerate((443, 5222, 8886)):
                    tcp(app, version, src, dst, target, 52000 + index)
            print(f'IPv4/IPv6 UDP 53,12000,27000,43000 + TCP 443,5222,8886: pass (start {attempt + 1})', flush=True)
        finally:
            library.hev_socks5_tunnel_quit()
            thread.join(5)
            assert not thread.is_alive(), 'native shutdown hung'
            app.close()
            native.close()
        assert result == [0], f'native exit status: {result}'


if __name__ == '__main__':
    main()
