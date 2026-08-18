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

Общий профиль использует публичный Yandex DoT. Направлять `dns-server` самого Shadowrocket на VPC DNS через
параллельный NetBird нельзя: на macOS DNS-сокет Packet Tunnel привязан к физическому интерфейсу и не попадает
в маршрут второго VPN. Для private Yandex MDB используется отдельный Host-модуль, который возвращает приложению
реальный private IP; дальнейшее TCP-соединение на `6432` принимает маршрут NetBird.

Установите модуль:

```text
https://raw.githubusercontent.com/Safronov-N/shadowrocket-roscomvpn-routing/main/netbird-mdb.module
```

В **Config → Modules → Edit Parameters** заполните `mdb_fqdn` и `mdb_ip`. IP можно получить через правильный
VPC DNS: `dig +short @10.10.100.2 <mdb_fqdn> A | tail -n 1`. Значения параметров хранятся локально и не попадают
в публичный репозиторий. После failover кластера IP требуется обновить.

Категория `geosite:whitelist` обновляется вместе с RoscomVPN. Генерация завершится ошибкой, если в ней появится
тип правила, который нельзя точно выразить через `always-real-ip`.

## Источники

- https://github.com/hydraponique/roscomvpn-routing
- https://github.com/hydraponique/roscomvpn-geosite
- https://github.com/hydraponique/roscomvpn-geoip
- https://github.com/1andrevich/Re-filter-lists
