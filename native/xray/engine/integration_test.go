package engine

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

type object = map[string]any

const testID = "00000000-0000-4000-8000-000000000001"

func fixture(t *testing.T, name string) object {
	t.Helper()
	dir := os.Getenv("WOT_CONFIG_FIXTURES")
	if dir == "" {
		t.Fatal("WOT_CONFIG_FIXTURES must point to the Kotlin compiler output")
	}
	data, err := os.ReadFile(filepath.Join(dir, name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var config object
	if err := json.Unmarshal(data, &config); err != nil {
		t.Fatal(err)
	}
	return config
}
func encoded(t *testing.T, config object) []byte {
	t.Helper()
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	return data
}
func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}

func TestCompiledConfigurations(t *testing.T) {
	for _, name := range []string{"vless", "vmess", "trojan", "shadowsocks", "vless-ws", "vless-grpc", "vless-httpupgrade", "vless-xhttp", "vless-reality", "ss2022"} {
		t.Run(name, func(t *testing.T) {
			var e Engine
			// Start checks protobuf construction, handlers and local TCP/UDP binding without a remote server.
			config := fixture(t, name)
			inbound := config["inbounds"].([]any)[0].(map[string]any)
			inbound["port"] = freePort(t)
			if code := e.Start(encoded(t, config)); code != OK {
				t.Fatalf("native config error code %d", code)
			}
			if !e.IsRunning() {
				t.Fatal("core did not start")
			}
			e.Stop()
			if e.IsRunning() {
				t.Fatal("core did not stop")
			}
		})
	}
}

func TestInvalidConfigurationAndRepeatedStop(t *testing.T) {
	var e Engine
	if e.Start([]byte(`{"inbounds":"invalid"}`)) != InvalidConfig {
		t.Fatal("accepted invalid config")
	}
	e.Stop()
	e.Stop()
}

func certificate(t *testing.T) ([]string, []string) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "localhost"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		DNSNames: []string{"localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")},
		IsCA: true, BasicConstraintsValid: true,
		KeyUsage:    x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	priv := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	return strings.Split(strings.TrimSpace(string(cert)), "\n"), strings.Split(strings.TrimSpace(string(priv)), "\n")
}

func TestTun2SocksTCPUDPAllProtocols(t *testing.T) {
	library := os.Getenv("HEV_TEST_LIBRARY")
	if _, err := os.Stat(library); err != nil {
		t.Fatal("Hev test library missing: ", err)
	}
	for _, family := range []string{"4", "6"} {
		host := "127.0.0.1"
		if family == "6" {
			host = "::1"
		}
		for _, port := range []int{443, 5222, 8886} {
			listener, err := net.Listen("tcp"+family, net.JoinHostPort(host, strconv.Itoa(port)))
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			go func() {
				for {
					conn, err := listener.Accept()
					if err != nil {
						return
					}
					go func() { defer conn.Close(); conn.SetDeadline(time.Now().Add(30 * time.Second)); io.Copy(conn, conn) }()
				}
			}()
		}
		for _, port := range []int{53, 12000, 27000, 43000} {
			conn, err := net.ListenPacket("udp"+family, net.JoinHostPort(host, strconv.Itoa(port)))
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			go func() {
				buffer := make([]byte, 65535)
				for {
					n, peer, err := conn.ReadFrom(buffer)
					if err != nil {
						return
					}
					if _, err := conn.WriteTo(buffer[:n], peer); err != nil {
						return
					}
				}
			}()
		}
	}
	cert, key := certificate(t)
	for _, protocol := range []string{"vless", "vmess", "trojan", "shadowsocks"} {
		t.Run(protocol, func(t *testing.T) {
			serverPort, socksPort := freePort(t), freePort(t)
			settings := object{}
			switch protocol {
			case "vless":
				settings = object{"clients": []any{object{"id": testID}}, "decryption": "none"}
			case "vmess":
				settings = object{"clients": []any{object{"id": testID}}}
			case "trojan":
				settings = object{"clients": []any{object{"password": "fixture-secret"}}}
			case "shadowsocks":
				settings = object{"method": "aes-128-gcm", "password": "fixture-secret", "network": "tcp,udp"}
			}
			stream := object{"network": "tcp", "security": "none"}
			if protocol == "trojan" {
				stream = object{"network": "tcp", "security": "tls", "tlsSettings": object{
					"certificates": []any{object{"certificate": cert, "key": key}},
				}}
			}
			serverConfig := object{
				"log":       object{"loglevel": "none", "access": "none", "error": "none"},
				"inbounds":  []any{object{"listen": "127.0.0.1", "port": serverPort, "protocol": protocol, "settings": settings, "streamSettings": stream}},
				"outbounds": []any{object{"protocol": "freedom"}},
			}
			var server, client Engine
			if code := server.Start(encoded(t, serverConfig)); code != OK {
				t.Fatalf("server code %d", code)
			}
			defer server.Stop()
			clientConfig := fixture(t, protocol)
			clientConfig["inbounds"].([]any)[0].(map[string]any)["port"] = socksPort
			outbound := clientConfig["outbounds"].([]any)[0].(map[string]any)
			field := "vnext"
			if protocol == "trojan" || protocol == "shadowsocks" {
				field = "servers"
			}
			remote := outbound["settings"].(map[string]any)[field].([]any)[0].(map[string]any)
			remote["address"], remote["port"] = "127.0.0.1", serverPort
			if protocol == "trojan" {
				// Trust only the ephemeral test CA; keep certificate verification enabled.
				tls := outbound["streamSettings"].(map[string]any)["tlsSettings"].(map[string]any)
				tls["disableSystemRoot"] = true
				tls["certificates"] = []any{object{"usage": "verify", "certificate": cert}}
			}
			if code := client.Start(encoded(t, clientConfig)); code != OK {
				t.Fatalf("client code %d", code)
			}
			defer client.Stop()
			command := exec.Command("python3", "../../tests/tun_roundtrip.py", library, strconv.Itoa(socksPort))
			output, err := command.CombinedOutput()
			t.Log(string(output))
			if err != nil {
				t.Fatalf("raw IP packet roundtrip failed: %v", err)
			}
		})
	}
}
