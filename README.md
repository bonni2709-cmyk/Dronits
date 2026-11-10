# WoT Blitz VPN — Android / Kotlin + NDK

Per-app VPN для **World of Tanks Blitz от Wargaming**, пакет
`com.wargaming.wot.blitz`. Остальные приложения не добавляются в VPN.
Android 8.0+ (API 26), ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64`.

## Архитектура

```text
com.wargaming.wot.blitz, IPv4/IPv6, TCP/UDP/DNS
  → Android VpnService (единственный addAllowedApplication)
  → TUN fd
  → HevSocks5Tunnel / lwIP (NDK, tun2socks)
  → SOCKS5 127.0.0.1:<случайный порт>, RFC 1929 + UDP ASSOCIATE
  → Xray-core (Go c-shared + JNI)
  → VLESS / VMess / Trojan / Shadowsocks сервер
```

Порты не ограничиваются: передаётся **весь IP-трафик игры**, в том числе
UDP `12000–43000`, TCP `443`, `5222`, `8886` и DNS. В Hev включён стандартный
SOCKS5 UDP relay (`udp: 'udp'`), в Xray inbound — `udp: true`. UDP не заменён
TCP-соединением, которое теряет границы датаграмм. На удалённом участке способ
инкапсуляции определяется протоколом и транспортом выбранного сервера.

**Удалённый сервер обязан разрешать UDP.** Клиент не может включить UDP
на сервере, где он отключён. Нужна собственная действующая ссылка конфигурации;
бесплатный VPN-сервер, доступ к аккаунту Wargaming и обход игровых ограничений
в проект не входят. Надпись «Подключено» означает работающий локальный TUN,
SOCKS5 и tun2socks, а не гарантированную доступность игрового сервера.

## Split tunneling и жизненный цикл

- Всегда создаётся свежий `VpnService.Builder` с единственным
  `addAllowedApplication("com.wargaming.wot.blitz")`. Нет перехода на VPN
  для всего устройства, `allowBypass()` или добавления других пакетов.
- Игра проверяется в UI, перед запуском и непосредственно при применении
  allowlist. Если её нет — **«World of Tanks Blitz не установлен»**. Гонка
  удаления пакета также прерывает подключение. Версия Lesta не добавляется.
- В политике есть IPv4/IPv6 default routes, DNS IPv4/IPv6 и MTU 1400.
  Android применяет эти маршруты только к разрешённому приложению.
- `BIND_VPN_SERVICE` установлен как permission самого сервиса; `<queries>`
  объявляет ровно игровой пакет. Для Android 14+ используется foreground
  service `specialUse`. Always-on намеренно не включён.
- Сначала проверяются конфигурация, запуск Xray и аутентифицированный
  SOCKS5 `UDP ASSOCIATE`. Только потом создаётся TUN и запускается relay.
  JNI relay ждёт инициализацию lwIP, а не просто создание pthread.
- «Подключено» публикуется только после успешного запуска обоих компонентов.
  Их локальная работоспособность контролируется раз в секунду. При остановке
  любого компонента сервис закрывает сессию и показывает ошибку.
- Отключение, отзыв согласия, отмена подключения, ошибка и уничтожение сервиса
  проходят через освобождение native worker, дубликата TUN fd, оригинального
  `ParcelFileDescriptor` и Xray. Очистка выполняется на IO в `NonCancellable`,
  не блокируя главный поток. Повторное подключение не накладывается на очистку.
- Все создаваемые Xray dialer/listener sockets проходят JNI callback
  `VpnService.protect(fd)`. UID клиента также отсутствует в VPN allowlist;
  рекурсии собственного прокси-трафика в TUN нет.

## Конфигурации и UI

Главный экран на Compose: большая кнопка «Подключить / Отключить», статус,
поле ссылки, переключатель видимости, очистка, проверка установленной игры,
системное согласие VPN и уведомления. Отказ в уведомлениях не блокирует VPN.
Запуск после callback выполняется только при `Lifecycle.State.RESUMED`.
Повторные нажатия, отмена и результаты без ожидающего запроса обработаны.

Поддерживаются **одиночные share links** до 32 768 символов:

- `vless://UUID@host:port?...`: `encryption`, TLS / REALITY, Vision flow,
  `sni`, `fp`, `alpn`, `pbk`, `sid`, `spx`, `pqv`.
- `vmess://BASE64(JSON)` (v2, AEAD / `aid=0`) и URI-форма с UUID.
- `trojan://password@host:port?...`, с TLS по умолчанию.
- `ss://BASE64(method:password)@host:port` (SIP002), plaintext userinfo
  и legacy base64 всей записи. AEAD AES-GCM / ChaCha20 и SS2022.
- IPv4, IPv6 в квадратных скобках, IDN-имена; корректное percent-decoding,
  сохраняющее `+` в паролях.
- Транспорты RAW/TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP, где они применимы
  к протоколу. Для XHTTP можно передать `extra` JSON через query.

Неподдерживаемые параметры не отбрасываются молча. SIP003 плагины,
устаревшие VMess alterId, старые транспорты, JSON-конфигурации целиком и
списки подписок не импортируются. `allowInsecure=1/true` запрещён: TLS
проверяет сертификат. VLESS/VMess ожидают UUID стандартного формата.
Введённая ссылка **не может добавить routing/direct fallback или приложение**.

Ссылка хранится только в памяти, не в `SavedStateHandle`, `rememberSaveable`,
файлах или SharedPreferences. Поворот экрана сохраняет черновик, завершение
процесса — нет. `FLAG_SECURE` запрещает снимки/превью; поле замаскировано,
клавиатура password, автокоррекция выключена. Буфер обмена автоматически не
читается. Ссылка и пароли не логируются и не входят в уведомления. В JNI
передаётся UTF-8 byte array, не modified UTF-8 `jstring`.

Локальный SOCKS5 на каждой сессии получает случайные порт, имя и пароль:
другие приложения не получают открытый unauthenticated proxy на loopback.
Конфигурация Xray и YAML relay передаются в памяти; production config-файлы
не создаются. Физическое обнуление всех копий строк JVM/Go не заявляется.

## Структура

```text
build.gradle.kts / app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/dronits/wotvpn/
  vpn/WotVpnService.kt              # VpnService, один пакет, native lifecycle
  vpn/GameTunnelPolicy.kt           # Единственный addAllowedApplication
  vpn/TunnelSettings.kt             # MTU / IPv4 / IPv6
  vpn/VpnState.kt / VpnClient.kt / WotGame.kt
  ui/MainActivity.kt                # Compose и системные разрешения
  ui/MainScreen.kt / MainViewModel.kt / MainUiState.kt
  core/ProxyManager.kt              # Xray → SOCKS5 UDP check → TUN → Hev
  core/XrayConfigCompiler.kt        # Share links → один Xray outbound
  core/Socks5Probe.kt
  core/NativeXray.kt / NativeTunnel.kt
native/
  versions.env                     # Точные версии/commit SHA
  xray/                            # Go module, engine, JNI C glue, native tests
  hev/wot-jni.c                    # fd ownership, readiness и join worker
  fetch-hev.sh / build-android.sh   # Только GitHub Actions
  tests/tun_roundtrip.py            # Сырые IP/TCP/UDP пакеты через lwIP
.github/workflows/build.yml
```

Xray **v26.11.9**, commit `676eaf66f8f05d4d558ee7a74b4736623d3feeb9`,
Hev **2.19.3**, commit `c5584feca8f6df70ee53eb2879e034b849a3d15a`.
Hev submodules фиксируются gitlinks upstream commit. Единственная адаптация
Hev, помимо JNI wrapper: readiness callback после подготовки lwIP tasks.
Xray upstream не изменяется. Native исходники скачиваются в игнорируемую
`.work/`, `.so` генерируются в `app/build/`, не коммитятся.

## Сборка APK — только GitHub Actions

**Локальная сборка в песочнице не выполняется.** Workflow запускается на push
в `main` / `arena/**`, pull request и вручную через **Actions → Android APK**.
Секретный GitHub-токен в исходниках или чате не требуется.

Среда: JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin / Compose compiler 2.1.21,
SDK / target 35, NDK 28.2.13676358, Go 1.27.3. Wrapper и Actions закреплены;
дистрибутив Gradle проверяется по SHA-256. Native ELF и APK ZIP проверяются
на 16 KiB page alignment.

CI:

1. Проверяет Go-модули, получает закреплённый Hev с submodules.
2. Собирает NDK tun2socks и Go/JNI `.so` для трёх ABI.
3. Выполняет `testDebugUnitTest lintRelease`; JVM-тесты также экспортируют
   конфигурации **production Kotlin compiler** для проверки реальным Xray.
4. Собирает Linux test library того же Hev и запускает native Go-тесты.
5. Выполняет `./gradlew --no-daemon assembleRelease`, `apksigner verify`,
   `zipalign -c -P 16` и публикует artifact **WoT-Blitz-VPN-<run_number>**.

В APK включаются лицензии и версии native зависимостей из реального Go
module graph и Hev. Release подписан постоянным **публичным debug-ключом**
из `signing/`, alias `androiddebugkey`, пароль `android`. Это личная тестовая
подпись, **не production signing**. Следующие APK можно ставить обновлением.

### Что проверяют packet tests

Из Kotlin-generated профилей поднимаются локальные Xray client/server для
VLESS, VMess, Trojan (с проверяемым тестовым TLS CA) и Shadowsocks. В настоящий
Hev/lwIP через datagram socketpair подаются сырые IP-пакеты. Проверяются
контрольные суммы, TCP handshake/данные и двусторонние UDP датаграммы:

- IPv4 **и** IPv6;
- UDP `53`, `12000`, `27000`, `43000` с бинарными payload;
- TCP `443`, `5222`, `8886`;
- повторный запуск/остановка relay и завершение worker.

Socketpair заменяет только источник TUN fd, не SOCKS5, протокол, lwIP или
удалённую сторону теста. Все тестовые адреса loopback; тесты не отправляют
пакеты в Wargaming. Это не заменяет Android per-UID routing и реальный бой.

### Проверка на устройстве после CI

1. Установить Wargaming-версию игры и APK, вставить действующую ссылку
   **с UDP-совместимым сервером**, выдать системное согласие VPN.
2. Проверить вход/чат, загрузку и реальный бой; потери/latency зависят от
   выбранного сервера. Доступ к реальному серверу в CI не предоставляется.
3. Убедиться, что браузер и другие приложения сохраняют прямой внешний IP.
4. Проверить отключение, отзыв VPN, поворот, отсутствие игры и повторный запуск.

Источники / лицензии: [Xray-core (MPL-2.0)](https://github.com/XTLS/Xray-core/tree/676eaf66f8f05d4d558ee7a74b4736623d3feeb9),
[HevSocks5Tunnel (MIT)](https://github.com/heiher/hev-socks5-tunnel/tree/c5584feca8f6df70ee53eb2879e034b849a3d15a).
