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

DIRECT-запросы сначала обслуживают DNS-резолверы dev VPC `10.10.100.2` и `10.10.102.2`, доступные через
NetBird. Поэтому special FQDN Yandex MDB получает корректный `NOERROR` и частный адрес. Публичные DNS оставлены
только резервом: они возвращают `NXDOMAIN` для приватных MDB-хостов и не могут использоваться параллельно с VPC DNS.

Категория `geosite:whitelist` обновляется вместе с RoscomVPN. Генерация завершится ошибкой, если в ней появится
тип правила, который нельзя точно выразить через `always-real-ip`.

## Источники

- https://github.com/hydraponique/roscomvpn-routing
- https://github.com/hydraponique/roscomvpn-geosite
- https://github.com/hydraponique/roscomvpn-geoip
- https://github.com/1andrevich/Re-filter-lists
