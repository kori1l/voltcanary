# Контракт управления и уточнения для реализации

Статус: **целевая спецификация, revision 2**, 2026-11-03. Реализация возобновлена
с G0; compiler/scalar kernels и bundle infrastructure реализуются отдельно.
Этот документ дополняет [VCL](configurable-operations-language.md),
[storage/adapters](configurable-operations-storage.md) и
[imports](configurable-operations-imports.md). UI и AI используют одни owner API.
Ниже — полный целевой контракт. Native COLLECTION и single-row MUTATION
reserve/prepare/apply/reconcile, dataset describe и ограниченный LIVE query profile
реализованы в [owner-срезе](../current/api/configurable-native-data.md).
Там же реализован read-only source binding schema journal; его legacy profile
не подтверждает normalized capture, coverage или RULE execution readiness.
Остальные endpoints и режимы остаются целевыми.
RULE identity reservation/inspection также реализованы в
[alert owner](../current/api/alerts-api.md#configurable-rule-identity-reservations);
Они не активируют rule. Reviewed automation grants профиля
`source-native-fields-grant-1` и их отзыв также реализованы в
[alert owner](../current/api/alerts-api.md#reviewed-automation-grants);
capture readiness и atomic alert/effect fencing остаются незавершёнными.
Reviewed private-reference evidence policies также реализованы отдельным
[owner-срезом](../current/api/alerts-api.md#reviewed-evidence-policies);
evidence storage/retention execution и RULE activation остаются целевыми.

## 1. Владельцы и транспорт

Gateway передаёт verified actor/account/workspace context. Ни JSON body, ни prompt
не назначают actor, tenant или grant. Для ресурсов owner проверяет scope заново.
Пути без `/api` соответствуют существующей схеме gateway; новые prefixes нужно
явно добавить в gateway. Не вводить общий execute-SQL/execute-tool endpoint.

| Method | HTTP | Owner |
| --- | --- | --- |
| datasets.reserve | POST /data/review-reservations | device-service |
| datasets.describe | GET /data/datasets/{id} | device-service |
| source_bindings.describe | GET /data/source-bindings/{id}?schemaRevision=1 | device-service |
| source_bindings.capture | GET /data/source-bindings/{id}/capture?schemaRevision=1 | device-service |
| datasets.query | POST /data/datasets/{id}/query | device-service |
| datasets.prepare_collection | POST /data/dataset-reviews | device-service |
| datasets.prepare_mutation | POST /data/datasets/{id}/mutation-reviews | device-service |
| datasets.apply | POST /data/reviews/{id}/apply | device-service |
| connections.prepare | POST /data/connection-reviews | device-service |
| connections.describe | GET /data/connections/{id} | device-service |
| connections.test | POST /data/connections/{id}/tests | device-service |
| imports.validate | POST /data/import-validations | device-service |
| imports.apply | POST /data/import-validations/{id}/apply | device-service |
| spatial.prepare | POST /spatial-feature-reviews | device-service |
| spatial.apply | POST /spatial-feature-reviews/{id}/apply | device-service |
| rules.compile | POST /analytics/configurable-rules/compilations | analytics-service |
| rules.dry_run | POST /analytics/configurable-rules/dry-runs | analytics-service |
| rules.reserve | POST /alert-rules/configurable/review-reservations | alert-service |
| grants.reserve | POST /alert-rules/configurable/grant-review-reservations | alert-service |
| grants.prepare | POST /alert-rules/configurable/grant-reviews | alert-service |
| grants.apply | POST /alert-rules/configurable/grant-reviews/{id}/apply | alert-service |
| grants.inspect | GET /alert-rules/configurable/grants/{id} | alert-service |
| grants.revoke | POST /alert-rules/configurable/grants/{id}/revoke | alert-service |
| grants.inspect_revocation | GET /alert-rules/configurable/grant-revocations/{id} | alert-service |
| evidence_policies.reserve | POST /alert-rules/configurable/evidence-policy-reservations | alert-service |
| evidence_policies.prepare | POST /alert-rules/configurable/evidence-policy-reviews | alert-service |
| evidence_policies.apply | POST /alert-rules/configurable/evidence-policy-reviews/{id}/apply | alert-service |
| evidence_policies.inspect | GET /alert-rules/configurable/evidence-policies/{id} | alert-service |
| rules.prepare | POST /alert-rules/configurable/reviews | alert-service |
| rules.activate/pause | POST /alert-rules/configurable/reviews/{id}/apply | alert-service |
| reviews.inspect/reconcile (data) | GET /data/reviews/{id}; POST /data/reviews/{id}/reconcile | device-service |
| reviews.inspect/reconcile (spatial) | GET /spatial-feature-reviews/{id}; POST /spatial-feature-reviews/{id}/reconcile | device-service |
| reviews.inspect/reconcile (rules) | GET /alert-rules/configurable/reviews/{id}; POST /alert-rules/configurable/reviews/{id}/reconcile | alert-service |

Каждый owner возвращает свой receipt. AI plan
только связывает receipts через уже существующий durable execution ledger.
Connection apply использует data/reviews; rule pause — отдельный review action,
не интерпретация изменённого RULE как команды pause. Произвольный URL reconcile
от модели не принимается: клиент выбирает owner route из server registry.

QUERY/IMPORT/COLLECTION/CONNECTION передаются как `{document:<VCL>}`. Query
cursor передаётся только в document.spec.cursor; новый query требует null.
Path datasetId обязан совпадать с document.spec.target.datasetId.
RULE compile принимает `{document:<RULE>,readSet:<owner-issued manifest>}`;
dry_run — `{bundleId:UUID,inputSnapshotId:UUID,effects:false}`. Произвольный
переданный клиентом readSet не доказывает доступ или snapshot consistency.

VCL требует UUID до prepare, а MUTATION также требует reviewId/idempotencyKey.
Для create клиент сначала вызывает reservation с закрытым union body:
`{kind:"COLLECTION",fieldKeys:string[1..64],idempotencyKey:string(1..160)}`,
`{kind:"CONNECTION",idempotencyKey:string(1..160)}` или
`{kind:"MUTATION",target:<dataset ref>,idempotencyKey:string(1..160)}`.
fieldKeys — уникальные локальные технические keys, не labels/SQL names.
Ответ содержит reviewId, expiresAt, resourceId и fieldIdsByKey (для COLLECTION)
или mutationId/createdRecordId (для MUTATION INSERT). Для rules.reserve body
`{kind:"RULE",outputKeys:string[0..64],idempotencyKey:string(1..160)}` резервирует
ruleId и derived fieldIds для measure outputs. Никаких target effects нет.
Повтор той же reservation возвращает те же IDs; другой body при том же key —409.
Затем prepare получает полный VCL и reservationId=reviewId в envelope.
UPDATE существующих COLLECTION/CONNECTION/RULE использует текущие resource IDs,
резервируя только новые field/output IDs при необходимости через тот же protocol
с дополнительным targetId и expectedRevision. Target metadata проверяет owner.
Реализованный RULE create reservation возвращает `reviewId,ruleId,receiptId,
outputFieldIdsByKey,expiresAt,status`; lease задаёт DB clock, replay не продлевает
его. Grant/review/activation остаются отдельными owner-проверками.
Keys reservation и effect разделены operation namespace, но финальный MUTATION
сохраняет исходный key.
Никакой циклической зависимости «нужен review, чтобы создать review» нет.

## 2. Review, preconditions и эффект

Prepare response содержит reviewId, revision (integer ≥1), status=PREPARED,
expiresAt, semanticDigest, reviewDigest, readSet, typedDiff, impact, effects,
requiredCapabilities, targetPrecondition и reservedIds. UUIDs назначает owner.
ReadSet entries: owner, resourceId, resourceRevision, schemaRevision (nullable),
snapshotId/checkpoint (nullable), accessScopeDigest. Nullable checkpoint означает
отсутствие этой гарантии, а не "текущий достаточно свежий".

Для datasets/spatial/rules apply body — ровно
`{reviewRevision,reviewDigest,idempotencyKey}`. Actor consent
связан с этим digest сервером в существующем механизме review/confirmation.
Body не содержит `approved:true`, actor или заменённый VCL. Owner использует
сохранённый документ, reauthorize и сравнивает все preconditions. Изменённые
порог, labels, geometry, source, recipients или destructive impact требуют нового
review. Expired review возвращает REVIEW_EXPIRED; permissions revoked — DENIED.
Для MUTATION apply key обязан совпадать с сохранённым document key.
imports.apply имеет собственный закрытый body:
`{reviewId,reviewRevision,reviewDigest,manifestDigest,idempotencyKey}`, связывая
path validationId с сохранёнными review/manifest; эти поля не заменяют manifest.
rules.prepare получает `{action:"ACTIVATE",reservationId,bundleId,grantId,expectedRuleRevision}`
или `{action:"PAUSE",ruleId,expectedRuleRevision,expectedEpoch}`; grant принадлежит
alert-service. ACTIVATE проверяет compiled RULE и reservation, а не latest bundle.
connections.prepare и datasets.prepare_collection принимают VCL+reservationId.

Grant owner profile `source-native-fields-grant-1` резервирует отдельные
reviewId/grantId/receiptId через `{kind:"AUTOMATION_GRANT",ruleReviewId,idempotencyKey}`.
RULE reservation обязан принадлежать текущему actor/account и сохранять исходный
lease. Grant prepare принимает `{reservationId,definition:{sourceBindings:[{id,
schemaRevision,fieldIds}],datasets:[{id,schemaRevision,fieldIds}],expiresAt}}`.
Как минимум один source binding обязателен; оба списка и наборы field IDs замкнуты
и ограничены. Identity, ruleId, scope/read manifests и IDs назначают/проверяют owners,
а не model JSON. Source/native owner checks пересекаются с текущим account scope;
unsupported exact numeric fields и foreign fields не дают новых прав.
Grant lifetime задаётся явно UTC instant с microsecond precision, не больше30 дней;
review lease не продлевается и ограничен исходным RULE reservation. Изменённый
preview повышает review revision и меняет digest. Apply использует только сохранённый
scope и обычный `{reviewRevision,reviewDigest,idempotencyKey}` после fresh owner
checks. Grant permission не утверждает capture/evaluator readiness.
Этот первый профиль разрешает только owner-local ALERT, без delivery channels.
Отзыв принимает `{expectedEpoch:string,idempotencyKey}` и линейризует новый epoch
в grant owner до ACK. Уже завершённый create receipt не становится текущим состоянием
grant после revoke. Inspect/reconcile grant reviews использует фиксированные
`GET /alert-rules/configurable/grant-reviews/{id}` и
`POST /alert-rules/configurable/grant-reviews/{id}/reconcile`.

Первый целевой evidence-policy profile `private-reference-evidence-1` резервирует
`reviewId,policyId,receiptId` через `{kind:"EVIDENCE_POLICY",grantId,
expectedGrantEpoch:string,idempotencyKey}`. Grant owner проверяет текущего actor/
account и exact epoch. Policy prepare принимает `{reservationId,definition:{mode:
"PRIVATE_REFERENCES",retentionDays:integer(1..30),maxObservationReferences:integer(1..100)}}`.
Grant/rule/scope IDs берутся только из сохранённой reservation; поле disclosure
нельзя расширить до values, recipients, public sharing или delivery channels.
Policy authoring lease —15 минут по свежему DB clock и не позднее grant expiry;
это отдельный review, который не продлевает grant. Apply использует обычный exact
revision/digest/key envelope и обновляет source/grant owner checks.
Создание policy и неизменяемый receipt атомарны после durable APPLYING. Inspect/
reconcile review: `GET /alert-rules/configurable/evidence-policy-reviews/{id}` и
`POST /alert-rules/configurable/evidence-policy-reviews/{id}/reconcile` с `{}`.
Policy inspect возвращает immutable policy revision1 и effective usability при
том же grant epoch. Grant revoke/expiry лишает policy usability; новый grant epoch
не переносится на старую policy автоматически. Policy metadata не создаёт evidence,
не выполняет retention cleanup исходного capture journal и не активирует RULE.
Этот ограниченный profile реализован в alert owner; он не завершает полный RULE/
evidence lifecycle. Повтор reservation с исходным key возвращает прежний owned
receipt даже после grant revoke/expiry, не восстанавливая usable authority.
spatial.prepare использует geometry/cost schema опубликованного spatial owner
registry; применение разрешено только через его review, не через COLLECTION.

Review states: RESERVED → PREPARED|EXPIRED|CANCELLED;
PREPARED → APPLYING|EXPIRED|CANCELLED; APPLYING → SUCCEEDED|FAILED|UNKNOWN.
UNKNOWN разрешается только owner reconciliation,
а не повторным планированием. PARTIAL возможен для import/saga job, не для одной
атомарной native mutation. Job status и review authorization — разные сущности:
202 содержит jobId, reviewId, receiptId и status; poll job по
GET /data/jobs/{jobId}, /spatial-features/jobs/{jobId},
/alert-rules/configurable/jobs/{jobId} или /analytics/configurable-rules/jobs/{jobId}
по фиксированному owner/type registry.
ReceiptID резервируется до попытки эффекта. UNKNOWN не terminal success/failure.
Успешный owner receipt содержит exact created/updated IDs, revisions и outcome.

Не требуется ещё одно глобальное хранилище review: типизированные owner records
и существующий execution ledger. Ключ dedup = account + owner + operation +
idempotencyKey. Другой canonical payload при том же ключе →409 KEY_REUSED.
Response replay тоже авторизуется; receipt не становится способом обхода revoke.

## 3. Зависимые шаги AI и новые ресурсы

Plan draft может использовать localRef вида `{stepId,outputName}` только в
типизированном plan envelope. В VCL placeholders запрещены. Owner сначала
резервирует IDs для draft collection/fields; начальная schemaRevision=1,
expected prior revision=0 означает CREATE, не существующую опубликованную schema.
Черновые IDs принадлежат scope/review и недоступны обычным list/query.

Compilation against draft metadata разрешена для preview со статусом DRAFT_ONLY;
это не активируемый bundle. Plan сохраняет symbolic draft и resolution map.
После SUCCEEDED creation receipt проверяет exact reserved IDs/schema и разрешает
dependent step. Следующий owner prepare компилирует production документ заново
по опубликованной metadata. Входящий receipt UNKNOWN/FAILED блокирует dependent
apply. Нельзя substitute latest object with same label. Изменение зависимостей
требует нового review. Partial saga показывает созданное; автоматическое удаление
используемого объекта в качестве compensation запрещено.

## 4. Полнота, ошибки и повторы

Query response: rows, nextCursor nullable, readManifest, completeness,
truncationReason nullable. completeness=COMPLETE|PARTIAL|UNKNOWN относится к
результату всего query; nextCursor означает PARTIAL до исчерпания того же snapshot.
Пустая страница не доказывает отсутствия записей. Полная выборка под одним scope
не доказывает полноты бизнес-фактов для другого scope. LIVE pagination не может
дать complete-negative assertion для temporal rule. Eligibility для rule зависит
от coverage/projection contract, а не только от rows.length.

Error envelope: `{code,stage,path,retryable,correlationId,details}`; path — JSON
Pointer или null. details — allowlisted безопасные IDs/choices, без raw source
values/SQL/credentials. Неверная форма=400, unauthenticated=401, denied=403,
неизвестный или скрытый resource=404 без подтверждения его существования,
stale/CAS/key conflict=409, отсутствующий обязательный precondition=428,
semantic/type/unit/capability error=422, exhausted quota=429,
source unavailable before dispatch=503. После возможной dispatch ошибки транспорта
не означают rollback: сохранённый review/job возвращает UNKNOWN с receiptId.
Polling использует Retry-After и bounded server retry policy; retryable не даёт
разрешения сменить key или повторить effect без verified adapter contract.

## 5. Проверяемость и источник истины

Нормативный порядок: VCL grammar → semantic compiler/authorized metadata →
owner capability/precondition validation → reviewed effect → receipt. JSON Schema
не проверяет remote truth, доступ, PostgreSQL locks или runtime event outcomes.
Изменённый draft не претендует на совместимость с ещё не реализованной revision1;
production numeric AST и существующие review APIs не переинтерпретируются.

`npm run vcl:check` требует Node из root package engines и PowerShell7 (`pwsh`).
Проверяет [11 документов](configurable-operations-language.examples.json) и
[33 отрицательных примера](configurable-operations-language.conformance.json):
11 grammar rejection и 22 grammar-valid semantic error, fixture field/type/unit
bindings, graph/session coherence и actual artifact hashes. Это documentation
guard, не backend compiler/evaluator. Он не доказывает все scalar kernels,
canonicalization, ACL, distributed recovery или live-provider quality.
G0–G8 обязаны перенести эти векторы в реализацию и выполнить
[runtime acceptance](configurable-operations-acceptance.md).
