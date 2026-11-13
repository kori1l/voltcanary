# Проверяемая идентичность: design

## Evidence and scope

Проверены device/security/AuthenticatedUserResolver.resolve (приоритет
fromGatewayHeaders), gateway/filter/JwtAuthenticationGlobalFilter (очистка и
добавление заголовков), docker-compose.yml (порт device-service), controllers
alert-service и scripts/device-variable-smoke.ps1 (header-only fixtures).
Это подтверждает опасный путь доверия, а не результат внешнего penetration test.
При реализации составить полный реестр всех потребителей user/account/role
headers, включая simulator, telemetry, analytics и внутренние адаптеры.

## Decision

Пользовательские APIs проверяют Bearer JWT существующим JwtAccessTokenService:
signature, issuer, audience, expiry, token type и key id. Subject/role берутся
только из проверенных claims. Account, membership и grants разрешаются owning
services; accountId не считается равным userId без подтвержденного контракта.
Поддельные вспомогательные identity headers игнорируются, не переопределяют
claims и не дают fallback при отсутствующем/невалидном JWT. Gateway сохраняет
очистку входящих заголовков. AI и прочие адаптеры передают пользовательский JWT.

Выделить небольшой общий механизм верификации и локальные адаптеры к actor DTO;
не дублировать правила владения, не добавлять authorization microservice.
Проверки действующего account и sharing остаются в domain application services.
Идентичность JWT сама по себе не гарантирует мгновенный отзыв access token:
сохранить и документировать существующие expiry/revocation/account-state правила.

Разделить route inventory: user, collector, internal, public health. Internal
service token разрешает только объявленные internal contracts, не impersonation
на публичных user APIs. Проверить легитимные вызовы, которым раньше хватало
X-User-Id; мигрировать на JWT либо узкий уже аутентифицированный internal API,
не открывать универсальный endpoint «выполнить от имени любого user».

## Testability and migration

Для живого локального стека использовать регистрацию/login выделенного test
account через identity/gateway; refresh через обычный API. Для изолированных
Java integration tests общий common-test helper подписывает JWT тестовым private
key, соответствующий public key подключается только к тестовому контексту.
Unit tests могут конструировать actor напрямую, если HTTP boundary не тестируется.
Фикстуры keys не становятся доверенными production keys; запретить запуск обычного
application profile с тестовым auth bypass. Никакого trust-user-headers флага.

Обновить header-only positive tests, сохранив отдельные negative spoofing tests.
Мигрировать device-variable/recovery/replica/access, dashboard/workspace,
vehicle, ingestion и ML verification scripts по фактическому inventory. Secrets,
access/refresh tokens не писать в summary, logs или Git. Сохранить user-approved
API/browser/CLI automation: create, inspect, cleanup в выделенном аккаунте.
Cleanup использует tracked IDs, не удаляет ресурсы других accounts.

Local диагностические порты остаются доступны через loopback или port-forward
с JWT. Проверить hostname/container access и smoke port overrides. Сборка и
проверка stack должны быть воспроизводимы, без ручного выпуска токена на каждый шаг.

## Acceptance and rollback

Через gateway и напрямую повторить read/create/update/delete для двух accounts:
header-only и invalid JWT отклоняются, JWT A плюс headers B остается A; foreign
IDs отклоняются. Проверить spoofed ADMIN, account/workspace headers, collector и
internal boundaries. Реальный smoke создает устройство, приватный dashboard и
источник, читает данные и очищает fixture без обхода. Изменения общих модулей
требуют backend mvn test; затронутый ML verification path — полного Docker gate.
Rollback возвращает согласованные binaries/config/scripts; не восстанавливать
header-only bypass как штатный recovery method. Тестируемость — acceptance gate.

