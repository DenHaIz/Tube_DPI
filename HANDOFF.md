# Tube — передача проекта

Документ для того, кто продолжит работу: что это, как устроено, как собирать и выпускать, где грабли.

## Что это

Android-приложение для просмотра YouTube в России без внешних серверов. Работает по принципу ByeDPI/zapret: локальный VPN только для выбранных приложений (YouTube, ReVanced, Morphe, Music, GmsCore) пропускает их трафик через byedpi, который запутывает DPI провайдера.

- Репозиторий: https://github.com/DenHaIz/Tube_DPI (релизы с APK).
- Пакет `com.tube.dpi`, minSdk 26, targetSdk 36, compileSdk 37. Лицензия GPL-3.0.
- Второй этап (свой клиент YouTube на NewPipeExtractor с необязательным входом в Google) **отложен** владельцем: хватает автозапуска вместе с YouTube.

## Стек

Kotlin + Jetpack Compose (BOM 2026.09.00), AGP 9.4.1, Gradle 9.8.2, NDK 29.0.14206865, DataStore, Haze (стекло), Robolectric + Roborazzi. Без DI-фреймворков и навигации: ручной `AppContainer`, один экран и стеклянные листы. Дизайн-основа перенесена из Woody (`..\Android TODO`).

## Устройство

```
YouTube / ReVanced / Morphe ─► TUN (VpnService, addAllowedApplication)
   ─► hev-socks5-tunnel (mapdns: DNS отвечает сам, в SOCKS уходит имя сайта)
   ─► SOCKS5 127.0.0.1:<свободный порт> ─► byedpi (отдельный процесс) ─► интернет
                                   └─ имена резолвит через LocalDns (127.0.0.1) → DoH 8.8.8.8 / 1.1.1.1
```

| Модуль | Что внутри |
|---|---|
| `core-byedpi` | byedpi (submodule) собирается **исполняемым** `libbyedpi.so` (свой Gradle-таск `BuildByeDpi`); `ByeDpiProcess`, `StrategyArgs` (разбор/проверка аргументов), `Strategies` (19 своих + 60 из ByeByeDPI в `ByeByeDpiPack`), `StrategyTester`, `ChromeHello`, `LocalDns`, `Socks5`; `assets/fake` — фейковые ClientHello/QUIC из zapret |
| `core-tunnel` | hev-socks5-tunnel (submodule, ndk-build) + JNI `TProxyService`, генератор `TunnelConfig` |
| `core-ui` | тема `TubeTheme`, стекло, пружины, `GlassSheet`, `LiquidSegmented`, строки настроек (`ToggleRow`, `PermissionRow`, `PillButton`, `SheetTitle`) |
| `app` | `vpn/TubeVpnService`, `VpnController`, `TunnelState`; `autostart/` (служба спецвозможностей + чистая `AutoStartPolicy`); `strategy/AutoPick`, `StrategyWatchdog`; `data/` (настройки, `NetworkMonitor`, `ResultsStore`); `update/UpdateChecker`; `tile/`, `LaunchActivity` (ярлык); `ui/` |

Ключевые решения и почему:
- **byedpi — отдельный процесс, а не библиотека.** У него глобальное состояние (params, getopt). Процесс изолирует падения и позволяет тестеру запускать 6 стратегий параллельно на разных портах. APK собран с `jniLibs.useLegacyPackaging = true`, иначе исполняемый файл не распакуется в `nativeLibraryDir`.
- **DNS через DoH.** С февраля 2026 национальный DNS (НСДИ) не отдаёт youtube.com. `getaddrinfo` в byedpi подменён при линковке (`-Wl,--wrap=getaddrinfo`, `core-byedpi/src/main/cpp/tube_dns.c`): если задан `TUBE_DNS`, запрос уходит на `LocalDns` приложения, тот ходит в DoH по IP (без SNI). Исходники byedpi не патчим.
- **mapdns в hev** выдаёт приложениям адреса из 198.19.0.0/16 (не 100.64/10 — это CGNAT мобильных операторов) и передаёт в SOCKS имя сайта.
- **QUIC** по умолчанию запрещён (`byedpi -U`): YouTube уходит на TCP, где работает desync.
- **Порты byedpi случайные свободные** (`ByeDpiProcess.freePort`) — и у VPN, и у тестера: фиксированные порты сталкивались (сторож и «Проверить» одновременно → «ошибка»). `start` ждёт, пока порт освободится, `stop` ждёт смерти процесса.
- **byedpi резолвит имена синхронно в единственном потоке** — пока ждёт DNS, стоят все соединения. Поэтому `LocalDns` держит keep-alive к DoH, отдаёт устаревший ответ сразу (обновляя в фоне, до суток) и при старте VPN/подбора заранее запрашивает адреса YouTube и кластера видеоузлов (`prefetch`).
- **Tube не входит в собственный VPN**, поэтому соединения byedpi идут напрямую, `protect()` не нужен.

### Подбор стратегий (`AutoPick` + `StrategyTester`)

1. Проверка системного DNS и поиск **настоящего видеоузла** сети: страница `youtube.com/watch` читается через текущую/рабочие стратегии до первой ссылки `rrN---sn-….googlevideo.com` (~790 КБ от начала страницы). Ответ `redirector/report_mapping` зашифрован и шифр меняется — не использовать.
2. Пробы для каждой стратегии (все должны пройти):
   - `www.youtube.com` и `i.ytimg.com`: полный TLS (Java) + загрузка > 64 / 8 КБ (видно замедление «замирает после 16 КБ»);
   - видеоузел: TLS + `/generate_204`;
   - сайт, `youtubei.googleapis.com` (API приложения), превью и видеоузел: **ClientHello как у Chrome** (`ChromeHello`, шаблон `tls_clienthello_vk_com_kyber.bin` ~1.8 КБ с Kyber и GREASE ECH, SNI подставляется) — сервер должен ответить ≥ 2 КБ. На каждый узел **три** ClientHello с разным порядком расширений: SNI в начале, SNI в конце (после key_share и ECH, во втором TCP-сегменте) и случайный — Chrome/Cronet переставляет расширения в каждом соединении.
   Без проверки «как у Chrome» в v0.2 часть стратегий проходила тест, но не пропускала видео в приложении: короткий Java-ClientHello DPI разбирает иначе, чем большой ClientHello Cronet. В v0.3 порядок расширений был всегда как в шаблоне (SNI ~150-й байт) — стратегии с фиксированными смещениями проходили тест, а в YouTube работали через раз.
   - Второй круг (`AutoPick.verify`): прошедшие (до 16) проверяются ещё раз по две за раз; не прошедшие повторно уходят из «рабочих».
3. Результаты пишутся в `ResultsStore` (`filesDir/results.json`) по ключу сети (`NetworkMonitor`: Wi-Fi — шлюз + DNS роутера, мобильная — MCC/MNC), вместе с выбранной для сети стратегией и видеоузлом.

### Сторож (`StrategyWatchdog`, запускается из `TubeVpnService`)

Первая проверка через 15 с после включения, дальше раз в 10 минут — пробы через работающий прокси. Два провала подряд (повтор через 15 с) при живом интернете (`ya.ru`, `gstatic`): пробует до 6 рабочих стратегий этой сети, если нет — полный подбор в фоне (не чаще раза в час). Провал в VPN записывается в результаты сети — стратегия уходит из «рабочих». При смене сети подставляет запомненную для неё стратегию. Смена — через `ACTION_RESTART` сервиса + уведомление.

### Автозапуск

`AutoStartService` получает только `TYPE_WINDOW_STATE_CHANGED` (имя пакета, без содержимого окон). Логика в `AutoStartPolicy` (покрыта тестами): включить при выходе выбранного приложения на экран; выключить через N минут после ухода, только если включал автозапуск и через туннель нет трафика (фоновое воспроизведение).

### Обновления

`UpdateChecker` читает `api.github.com/repos/DenHaIz/Tube_DPI/releases/latest` (если API недоступен или лимит 60 запросов/час — редирект `github.com/…/releases/latest` → `/tag/vX.Y`, APK `Tube-X.Y.apk`), сравнивает `tag_name` с `versionName`, качает первый `.apk` из ассетов в `cacheDir/updates` и открывает системный установщик через FileProvider (`${applicationId}.files`). **Значит, у каждого релиза должен быть APK `Tube-X.Y.apk` и тег `vX.Y`.** До 0.4 проверка всегда падала: заголовок `Accept` ставился после `responseCode` («Already connected»).

## Сборка на сервере разработки

Тулчейн общий с Woody: `.toolchain` — junction на `..\Android TODO\.toolchain` (создаётся `env.ps1`). Путь без пробелов обязателен: ndk-build падает на пробеле в «Android TODO», а 8.3-имена Gradle разворачивает обратно. NDK 29 доставлен в общий SDK; `sdkmanager` устарел — `cmdline-tools\latest\bin\android.exe sdk install <пакет>`.

```powershell
git submodule update --init --recursive
. .\env.ps1
.\gradlew.bat testDebugUnitTest                         # логика (Robolectric, @Config(sdk = [35]))
.\gradlew.bat :app:testDebugUnitTest -Pscreenshots      # скриншоты → app\build\outputs\roborazzi
.\gradlew.bat :core-byedpi:testDebugUnitTest -Pnetwork  # проверки через настоящий интернет (DoH, пробы, Chrome-hello)
.\gradlew.bat :app:lintDebug :app:assembleRelease
```

- Эмулятора нет. Визуальная проверка — только скриншоты Roborazzi; сеть — тесты `-Pnetwork`.
- Сервер стоит в Финляндии: YouTube здесь не блокируется, поэтому `-Pnetwork` проверяет только «трубопровод» (SOCKS, TLS, DoH, ClientHello), а не обход DPI. Работу стратегий проверяет владелец на телефоне в РФ.
- Robolectric без `@Config(sdk = [35])` падает на SDK 36 (`FileDescriptor internals`).

## Релиз

1. Поднять `versionCode`/`versionName` в `app/build.gradle.kts`.
2. Тесты, `lintDebug`, `assembleRelease`; скопировать APK в корень как `Tube-X.Y.apk` (в git не попадает).
3. Коммит **только от `DenHaIz <denister173@gmail.com>`**, без упоминаний Claude; тег `vX.Y`; push.
4. `gh release create vX.Y Tube-X.Y.apk --repo DenHaIz/Tube_DPI --title "Tube X.Y" --notes ...` — описание короткое, на русском и английском.
5. Владелец разрешил выкладывать готовые версии **без отдельного вопроса**.

Release подписан debug-ключом общего тулчейна (`.toolchain/android-user-home/debug.keystore`). Не терять: APK с другой подписью не встанет поверх.

## Грабли

- **Bash-инструмент портит `\\` в heredoc** (превращает в настоящие переводы строк/склеивает строки). Для правок с экранированием писать Python-скрипт в файл и запускать его, либо пользоваться Edit.
- Шаблоны/фейки в `assets/fake` подставляются в аргументы как `@fake/<файл>`, фейковый SNI — как `{sni}` (`ByeDpiProcess.start`).
- `-S` (md5sig) может не работать на ядрах Android — такие стратегии просто не проходят тест.
- С ноября 2026 у ТТК, Ростелекома, Дом.ру тормозят видео вне кэша GGC провайдера — это не DPI, стратегии не помогут.
- Android 13+ для APK не из магазина: прежде чем включать службу спецвозможностей, нужно «Сведения о приложении → ⋮ → Разрешить ограниченные настройки».
- Предпочтения владельца по интерфейсу: почти весь текст по центру, коротко, без мелкого шрифта (`labelSmall` не использовать для пояснений), главный экран без прокрутки.

## История версий

- **0.1** — VPN + byedpi + hev, автозапуск, подбор, DoH для byedpi, ~80 стратегий.
- **0.2** — результаты по сетям, сортировка, сторож, обновления из GitHub, вёрстка по центру.
- **0.3** — проверка ClientHello «как у Chrome» и настоящего видеоузла (стратегии больше не помечаются рабочими зря), главный экран без прокрутки, настройки по центру без выхода за края.
- **0.4** — ClientHello с перестановкой расширений (3 варианта на узел) и проверка API youtubei, второй круг проверки, сторож проверяет через 15 с и помечает провал в VPN, случайные свободные порты, быстрый DNS (keep-alive, устаревший кэш, prefetch), исправлена проверка обновлений + запасной путь через github.com.
