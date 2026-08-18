# Shadowrocket RoscomVPN Routing

Репозиторий автоматически преобразует актуальный профиль RoscomVPN `HAPP/DEFAULT.JSON`
в нативные списки правил Shadowrocket.

## Автообновление

GitHub Actions каждые 6 часов:

1. Загружает актуальный `HAPP/DEFAULT.JSON` из `hydraponique/roscomvpn-routing`.
2. Определяет категории `BlockSites`, `ProxySites`, `DirectSites` и соответствующие GeoIP-категории.
3. Загружает данные из `roscomvpn-geosite` и текстовые CIDR из `roscomvpn-geoip`.
4. Преобразует их в `rules/BLOCK.list`, `rules/PROXY.list` и `rules/DIRECT.list`.
5. Преобразует `geosite:whitelist` в `always-real-ip` внутри `shadowrocket.conf`.
6. Коммитит только реальные изменения.

Если RoscomVPN добавит неподдерживаемое регулярное выражение, сборка завершится ошибкой,
а последняя рабочая версия списков останется доступной.

## Установка

Импортируйте конфиг в Shadowrocket:

```text
https://raw.githubusercontent.com/Safronov-N/shadowrocket-roscomvpn-routing/main/shadowrocket.conf
```

В Shadowrocket включите фоновое обновление конфигурации. Для фоновой работы iOS должна разрешать
**Settings → General → Background App Refresh → Shadowrocket**.

Однократно откройте **Settings → GeoLite2 Database** и задайте **Country URL**:

```text
https://raw.githubusercontent.com/Loyalsoldier/geoip/release/Country.mmdb
```

Поле **ASN URL** можно оставить пустым. Без внешнего URL конфиг использует встроенную Country-базу Shadowrocket.

## DNS, whitelist и параллельные VPN

Для обычных доменов конфиг использует Fake-IP. Домены из `geosite:whitelist` автоматически добавляются
в `always-real-ip`, но по-прежнему остаются в `DIRECT.list`. Это позволяет внутренним сервисам с частными
DNS-ответами, например `dev.netmonet.co → 10.10.100.7`, попасть в маршрут NetBird или другого параллельного
VPN-клиента вместо повторной обработки Fake-IP внутри Shadowrocket.

Общий профиль использует публичный Yandex DoT. Направлять `dns-server` самого Shadowrocket на DNS приватной
VPC через параллельный NetBird нельзя: на macOS DNS-сокет Packet Tunnel может быть привязан к физическому
интерфейсу и не попасть в маршрут второго VPN.

### Private Yandex MDB через NetBird

Для `*.mdb.yandexcloud.net` одного `always-real-ip` или `[Host]` недостаточно. Публичный DNS не видит private A,
а Shadowrocket 2.2.90 после переподключения может выдать JVM новый Fake-IP из `198.18.0.0/15`. Устойчивое решение —
направить всю зону `mdb.yandexcloud.net` в DNS нужной VPC:

1. Предпочтительно создайте в NetBird Nameserver Group с DNS-адресами нужной VPC, Match Domain
   `mdb.yandexcloud.net` и Distribution Group нужных клиентов.
2. Если изменить NetBird нельзя, на macOS создайте `/etc/resolver/mdb.yandexcloud.net` по шаблону
   [`examples/macos-resolver/mdb.yandexcloud.net.example`](examples/macos-resolver/mdb.yandexcloud.net.example).
   Системный resolver выберет его как наиболее специфичный и отправит запрос через маршрут NetBird.
3. Проверяйте системный путь командами `dscacheutil -q host -a name <mdb_fqdn>` и
   `nc -vz <mdb_fqdn> 6432`. Обычный `dig` на macOS может обходить scoped resolver и не подходит для этой проверки.

Правило `DOMAIN-SUFFIX,mdb.yandexcloud.net,DIRECT`, разрешение private IP и обход `10.0.0.0/8` уже включены
в профиль. `yandexcloud.net` исключён только из `always-real-ip`, чтобы публичный DNS не завершал private lookup
ошибкой; маршрутизационное правило `DIRECT` сохраняется. Модуль [`netbird-mdb.module`](netbird-mdb.module)
оставлен только как аварийный статический pinning:
он не исправляет FakeDNS сам по себе, а его IP потребуется менять после failover кластера.

Категория `geosite:whitelist` обновляется вместе с RoscomVPN. Генерация завершится ошибкой, если в ней появится
тип правила, который нельзя точно выразить через `always-real-ip`.

## Источники

- https://github.com/hydraponique/roscomvpn-routing
- https://github.com/hydraponique/roscomvpn-geosite
- https://github.com/hydraponique/roscomvpn-geoip
- https://github.com/1andrevich/Re-filter-lists
- https://docs.netbird.io/manage/dns/internal-dns-servers
- https://yandex.cloud/en/docs/managed-postgresql/qa/errors
