# Сторонние материалы и изменения

## Yandex Station Extended

Автор исходного проекта: **Марк Писаренко (hackalice), Yandex Station Extended**.

```text
Copyright (c) 2026 Марк Писаренко (hackalice), Yandex Station Extended
```

Источник: https://github.com/Marker284/yandex-station-extended/tree/main/contrib/station1-tv

Лицензия исходного репозитория: **Creative Commons Attribution 4.0 International (CC BY 4.0)**. Полный полученный текст лицензии с отказом от гарантий сохранён в [`remote/LICENSE.upstream`](remote/LICENSE.upstream).

Текст лицензии взят из upstream-ревизии `520fbccd68cb90a8c4f01d06bf38d9a7db915304`. Эта ревизия обозначает источник текста лицензии, а не утверждение о точной ревизии каждого локального снимка кода.

Импортированы исходники `StationRemote.java`, `VlessLink.java`, `index.html`, `remote-start.sh` и `build.py` из ТВ-набора для Станции 1. Для действующего сервера реконструкция исходника проверена совпадением собранного DEX с установленным.

### Локальные изменения

- В `remote/src/StationRemote.java` запуск Couchy заменён на Projectivy.
- В `remote/src/index.html` удалены карточка VPN, его раздел настроек и связанный обработчик формы.
- В `remote/src/remote-start.sh` компонент ТВ-лаунчера заменён на Projectivy; штатный режим Алисы сохранён.
- В `experiments/session-persistence/src/StationRemote.java` находятся отдельные незавершённые изменения хранения сессий. Они не являются частью установленного пульта.
- Добавлены локальные тесты, документация и очищенный профиль лаунчера.

Изменённые материалы не представлены как официальный релиз upstream. Исходный автор не заявлял одобрение этого проекта. Сведения об авторстве, лицензии и отсутствии гарантий сохраняются вместе с копиями исходников.

APK Projectivy, SmartTube, Zona, прошивки, JDK и D8/R8 в репозиторий не включены. Настройки Projectivy — это профиль интерфейса, а не исходный код или бинарный пакет самого лаунчера.
