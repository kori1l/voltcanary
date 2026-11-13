# Проверяемая идентичность пользователя без потери самостоятельного тестирования

## Why

Device AuthenticatedUserResolver принимает X-User-* до проверки Bearer JWT,
а Compose публикует сервисный порт. Аналогичные потребители заголовков есть в
других сервисах. Проверка владения ресурсом надежна только при достоверной
идентичности. Часть integration tests и smoke scripts использует этот обход.

## What Changes

- Проверять пользовательский JWT на каждом пользовательском API независимо от маршрута через gateway или прямого доступа.
- Не использовать X-User-*, X-Account-Id и workspace/capability headers как самостоятельное доказательство полномочий.
- Сохранить отдельную аутентификацию collector tokens и явно внутренних service APIs.
- Мигрировать fixtures и smoke scripts на настоящий login либо подписанные тестовые JWT; сохранить самостоятельное создание и очистку тестовых ресурсов по запросу пользователя.
- Ограничить локальные опубликованные служебные порты loopback там, где они нужны для диагностики; это дополнительная мера, не замена JWT.

## Capabilities

### New Capabilities

- `verified-user-identity`: Проверяемая идентичность на пользовательских API и авторизованная автоматизация тестирования.

### Modified Capabilities

None. Existing resource ownership and explicit sharing semantics remain authoritative.

## Dependencies

Нет. Security fix можно реализовать отдельно. Он является release prerequisite
для новых AI creation workflows, но подготовка остальных changes независима.

## Impact

Backend identity resolvers/controllers, common-security/common-test, gateway,
Compose/local deployment, scripts and integration tests. Сохраняются URL и JWT
контракты; header-only пользовательские вызовы намеренно перестают работать.
Production workload identity/mTLS и новая система ролей не входят в scope.

## Status

Реализация внесена: JWT boundary, отдельные внутренние чтения телеметрии,
миграция фикстур и live scripts, loopback diagnostic ports. Backend suites,
68 two-account gateway/direct checks, variable recovery/replica/access и полный
ML Docker regression gate пройдены. Проверки и migration matrix находятся в
[current security](../../../../docs/current/architecture/08-security.md).

