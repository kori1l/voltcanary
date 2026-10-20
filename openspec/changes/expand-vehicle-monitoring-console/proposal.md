# Автомобильный мониторинг

## Why

Пользователь подтвердил расширение работающего GPS-сценария до основных функций систем автомобильного мониторинга. Отдельный маркер недостаточен для работы с автопарком, анализа поездок и настройки эмуляции.

## What Changes

- Отдельный раздел Vehicles: все доступные автомобили на общей карте, поиск и фильтры движения/простоя/отсутствия данных, выбор автомобиля.
- Записанная история с курсором, воспроизведение по времени, поездки, расстояние, длительность, максимальная и средняя скорость, экспорт CSV.
- Сохраняемые круглые геозоны автомобиля, события въезда/выезда и превышения установленной скорости по принятым GPS-наблюдениям.
- Настраиваемый замкнутый маршрут GPS-эмуляции, остановки в точках, скорость, зажигание, уровень топлива и пробег.
- Сохраняемые настройки мониторинга; независимые ошибки, доступ, прекращение фоновых запросов и явные ограничения выборки.

## Capabilities

### New Capabilities
- `vehicle-monitoring-console`: fleet console, trips/playback/export, zones/events/settings.
### Modified Capabilities
- `vehicle-gps-simulation`: optional custom route/stops and generated vehicle metrics.
- `vehicle-position-telemetry`: accepted optional vehicle metrics and cursor reads.

## Impact

Existing simulator, ingestion, telemetry, frontend; existing dashboard vehicle-map remains compatible. No new service, broker topic or external paid provider. This change implements a defined product scope, not a claim of equivalence with every commercial fleet platform. Hardware protocols, remote engine commands, driver identity, billing, external notification deliveries and dispatch integrations require their own contracts and are outside this implementation.
