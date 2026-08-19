# Shadowrocket RoscomVPN Routing

Автообновляемая адаптация профиля RoscomVPN для Shadowrocket. Репозиторий преобразует категории
`HAPP/DEFAULT.JSON` в нативные списки Shadowrocket и публикует готовый конфиг без VPN-серверов и секретов.

## Установка

Импортируйте в Shadowrocket:

```text
https://raw.githubusercontent.com/Safronov-N/shadowrocket-roscomvpn-routing/main/shadowrocket.conf
```

Выберите своё VPN-подключение, примените конфиг и оставьте режим маршрутизации **Config**. Для автоматического
обновления включите фоновое обновление конфигурации в Shadowrocket и Background App Refresh в настройках iOS.

Конфиг использует текущий выбранный сервер: действие `PROXY` не содержит адресов, ключей или учётных данных.

## Маршрутизация

| Трафик | Действие |
| --- | --- |
| `BlockSites` / `BlockIp`, рекламные списки | `REJECT` |
| `ProxySites` / `ProxyIp`, Re-filter, проверки внешнего IP | `PROXY` |
| `DirectSites` / `DirectIp`, whitelist, национальные зоны, остальные IP РФ | `DIRECT` |
| Частные и NetBird-сети | исключены из TUN Shadowrocket |
| Всё остальное | `PROXY` |

Порядок правил важен: блокировки и принудительный прокси проверяются до `DIRECT` и `GEOIP,RU`.

## Автообновление

GitHub Actions каждые 6 часов:

1. Загружает актуальный `HAPP/DEFAULT.JSON`.
2. Проверяет ожидаемые `GlobalProxy`, `RouteOrder` и `DomainStrategy=IPIfNonMatch`.
3. Берёт версии geosite и geoip из URL самого профиля, а не из плавающей ветки.
4. Генерирует `rules/BLOCK.list`, `rules/PROXY.list` и `rules/DIRECT.list`.
5. Переносит `DnsHosts` в `[Host]`, а совместимую часть `geosite:whitelist` — в `always-real-ip`.
6. Проверяет структуру, CIDR, домены и защитные DNS-исключения перед публикацией.

Если upstream меняется несовместимо или отдаёт некорректные данные, сборка завершается ошибкой. Последняя рабочая
версия остаётся доступной.

## DNS, Fake-IP и whitelist

Обычные домены используют Fake-IP. Whitelist остаётся нужен, хотя `.ru` уже идёт напрямую: в категории есть
не-RU и внутренние домены, которым Shadowrocket должен вернуть реальный адрес, чтобы соединение принял NetBird
или другой параллельный VPN. Глобальный `always-real-ip = *` намеренно не используется.

Для эквивалентности HAPP `IPIfNonMatch` после всех доменных правил расположено правило
`SCRIPT,private-ip-if-non-match,DIRECT,requires-resolve`. Неизвестный домен сначала проверяется по доменным
спискам, затем скрипт получает реальный DNS-ответ. Адрес из
`10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`, `172.16.0.0/12` или `192.168.0.0/16` получает
`DIRECT` и передаётся системному маршруту. Ранние private-IP правила с `no-resolve` остаются для запросов по IP.

Домены проверок внешнего IP исключаются из `always-real-ip`, потому что они принудительно идут через `PROXY`.
Зона `mdb.yandexcloud.net` также исключена: выбор её приватного DNS решается scoped resolver, а не Fake-IP.

Основной DNS — публичный Yandex DoT. Ошибка DNS для `DIRECT` не переключает приватное имя на proxy resolver.
Записи `DnsHosts` из RoscomVPN генерируются в `[Host]` автоматически.

## Private Yandex MDB через NetBird

Для `*.mdb.yandexcloud.net` одного `always-real-ip` или `[Host]` недостаточно: публичный DNS не знает private A,
а статический IP ломает автоматический failover master. Нужен DNS нужной Yandex Cloud VPC.

Предпочтительный централизованный вариант — NetBird Nameserver Group:

- DNS-серверы нужной VPC;
- Match Domain `mdb.yandexcloud.net`;
- Distribution Group нужных клиентов;
- маршрут к DNS IP и разрешённые TCP/UDP 53 через routing peer.

Для отдельного Mac можно установить scoped resolver по инструкции
[`examples/macos-resolver/README.md`](examples/macos-resolver/README.md). Он направляет только эту DNS-зону через
NetBird. Профиль уже содержит ранний `DIRECT`, разрешает private DNS-ответы и исключает `10.0.0.0/8` из TUN.

Scoped resolver используют только приложения, работающие через системный resolver macOS. `dig`, некоторые
runtime и собственные DNS-клиенты могут его обходить, поэтому проверяйте разрешение из целевого приложения.
На iOS файл `/etc/resolver` недоступен — используйте NetBird Nameserver Group.

## GeoLite2

Для более свежей Country-базы можно указать в **Settings → GeoLite2 Database → Country URL**:

```text
https://raw.githubusercontent.com/Loyalsoldier/geoip/release/Country.mmdb
```

Поле ASN можно оставить пустым. Без внешнего URL используется встроенная база Shadowrocket.

## Ограничения

- Обобщённое regexp для односегментных private-hostname из `geosite:private` нельзя точно выразить текущими
  правилами Shadowrocket, поэтому оно пропускается. Полные private FQDN и IP-маршруты продолжают работать.
- Re-filter и дополнительный рекламный список загружаются клиентом напрямую из их веток `main`; их обновления
  не проходят через генератор этого репозитория.
- Scoped DNS не заменяет NetBird: resolver выбирает DNS, а NetBird обеспечивает маршрут и доступ.

## Разработка

```bash
kotlinc -script scripts/generate.main.kts
```

Сгенерированные `rules/*.list` и `shadowrocket.conf` не редактируются вручную. Pull request проверяется повторной
генерацией; расписание публикует только реальные изменения.

## Источники и лицензии

- [RoscomVPN routing](https://github.com/hydraponique/roscomvpn-routing)
- [RoscomVPN geosite](https://github.com/hydraponique/roscomvpn-geosite)
- [RoscomVPN geoip](https://github.com/hydraponique/roscomvpn-geoip)
- [Re-filter](https://github.com/1andrevich/Re-filter-lists)
- [NetBird DNS](https://docs.netbird.io/manage/dns/internal-dns-servers)
- [Yandex Cloud MDB DNS](https://yandex.cloud/en/docs/managed-postgresql/qa/errors)

Уведомления о лицензиях сторонних данных находятся в [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).
