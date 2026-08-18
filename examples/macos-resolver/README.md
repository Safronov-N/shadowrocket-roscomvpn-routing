# Scoped DNS для Yandex MDB на macOS

Этот resolver отправляет только запросы зоны `mdb.yandexcloud.net` в DNS нужной Yandex Cloud VPC.
Остальные домены продолжают использовать DNS Shadowrocket.

Перед установкой NetBird должен иметь маршрут к обоим DNS IP, а политика должна разрешать TCP и UDP 53.

## Установка

1. Скопируйте `mdb.yandexcloud.net.example` и замените `VPC_DNS_IP_1` и `VPC_DNS_IP_2`.
2. Создайте системный каталог и установите файл:

```bash
sudo mkdir -p /etc/resolver
sudo install -m 644 mdb.yandexcloud.net /etc/resolver/mdb.yandexcloud.net
```

3. Полностью перезапустите DBeaver или другой JVM-клиент, чтобы сбросить его DNS cache.

Проверка:

```bash
scutil --dns
dscacheutil -q host -a name <mdb_fqdn>
nc -vz <mdb_fqdn> 6432
```

Обычный `dig <mdb_fqdn>` может обходить scoped resolver macOS и не подходит для итоговой проверки.
Некоторые приложения также читают `/etc/resolv.conf` или используют собственный DNS-клиент. Для них отдельно
проверьте lookup внутри приложения; централизованной альтернативой служит NetBird Nameserver Group с Match Domain.

## Удаление

```bash
sudo rm /etc/resolver/mdb.yandexcloud.net
```

Без NetBird resolver недоступен, поэтому имена MDB будут завершаться ошибкой или тайм-аутом. Остальные DNS-зоны
эта настройка не затрагивает.
