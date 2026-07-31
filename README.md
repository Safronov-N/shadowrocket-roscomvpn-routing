# Shadowrocket RoscomVPN Routing

Репозиторий автоматически преобразует актуальный профиль RoscomVPN `HAPP/DEFAULT.JSON`
в нативные списки правил Shadowrocket.

## Автообновление

GitHub Actions каждые 6 часов:

1. Загружает актуальный `HAPP/DEFAULT.JSON` из `hydraponique/roscomvpn-routing`.
2. Определяет категории `BlockSites`, `ProxySites`, `DirectSites` и соответствующие GeoIP-категории.
3. Загружает данные из `roscomvpn-geosite` и текстовые CIDR из `roscomvpn-geoip`.
4. Преобразует их в `rules/BLOCK.list`, `rules/PROXY.list` и `rules/DIRECT.list`.
5. Коммитит только реальные изменения.

Если RoscomVPN добавит неподдерживаемое регулярное выражение, сборка завершится ошибкой,
а последняя рабочая версия списков останется доступной.

## Установка

Импортируйте конфиг в Shadowrocket:

```text
https://raw.githubusercontent.com/nikolai-safronov/shadowrocket-roscomvpn-routing/main/shadowrocket.conf
```

В Shadowrocket включите фоновое обновление конфигурации. Для фоновой работы iOS должна разрешать
**Settings → General → Background App Refresh → Shadowrocket**.

## Источники

- https://github.com/hydraponique/roscomvpn-routing
- https://github.com/hydraponique/roscomvpn-geosite
- https://github.com/hydraponique/roscomvpn-geoip
- https://github.com/1andrevich/Re-filter-lists
