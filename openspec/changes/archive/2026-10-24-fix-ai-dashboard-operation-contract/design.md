# Correct operation semantics without expanding planning authority

keep_review is an exclusive operation without resource IDs. The selected owner-
scoped READY parent is revalidated and copied exactly through the existing saved
review boundary. It is unavailable on initial creation or failed parents and
retains all pending work without applying or superseding it. READY candidates
retain pending goals until stage5 verifies them, so their presence cannot itself
block keeping a review. Keep does not invoke task-state restoreTo(). No
recomposition or execution occurs. New keep metadata declares an empty change mask
and no reflow while retaining the immutable definition/prepare payload and digest.

rename_widget accepts an existing key or @id of an earlier show/add result. The
server checks order/existence. Initial strict schema permits @references rather
than invented widget identities. Other explicit data targets retain existing-key
semantics. History inference includes visualization; an explicit target may
change its supported visualization. Period distinctions remain intact.

set_data adds only the server-resolved replacement device to dashboard scope.
Existing dashboard devices remain selected: changing one widget must not change
other widgets or remove their scope. Per-widget replacement binding is exact.

Provider schema bounds positions, widths, periods, views and integer ranges;
dataRef and targets stay server validated. Schema generation remains parity-tested
against sealed Java variants. Changed prompt version records the new contract.

Opt-in context removes unused templates and duplicate per-device metric/field
summaries before the unchanged input budget check. Authoritative data choices,
widget state, resource references and credential scanning remain in place.
Legacy generation keeps its existing projection.
