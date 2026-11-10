# Установка сервера

На чистом VPS (Ubuntu/Debian) от root:

    bash setup-server.sh

Скрипт сам поставит WireGuard, настроит сеть и выдаст ключ `shaurma://…`.
Откройте порт **51820/udp** в панели хостинга.

Новый ключ для другого человека:

    shaurma-add-client имя
