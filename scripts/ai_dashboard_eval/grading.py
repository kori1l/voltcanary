"""Independent outcome predicates; assistant success prose is never a grader."""
from copy import deepcopy

FIELDS = {"speed": "speedKmh", "doors": "transport.doorsOpen", "stop": "transport.nextStopId",
          "destination": "transport.destination"}


def definition(plan):
    actions = plan.get("actions", [])
    if len(actions) != 1 or actions[0].get("tool") not in ("CREATE_DASHBOARD", "UPDATE_DASHBOARD_DRAFT"):
        return None
    return actions[0].get("parameters", {}).get("definition")


def role(widget, selector):
    kind, _, field = selector.partition(":")
    widget_type = widget.get("type")
    value = widget.get("presentation", {}).get("vehicleField", widget.get("query", {}).get("metric"))
    if kind == "map":
        return widget_type == "vehicle-map"
    if kind == "note":
        return widget_type == "context-note"
    types = {"current": {"vehicle-value", "latest-value"},
             "history": {"vehicle-timeline", "time-series"}}
    return widget_type in types.get(kind, set()) and value == FIELDS.get(field, field)


def matches(doc, selector):
    return [w for w in doc.get("widgets", []) if role(w, selector)]


def ordered(doc, breakpoint="desktop"):
    return [x["widgetId"] for x in sorted(doc["layouts"][breakpoint], key=lambda x: (x["y"], x["x"]))]


def diff_paths(left, right, path=""):
    if type(left) != type(right):
        return [path]
    if isinstance(left, dict):
        result = []
        for key in sorted(left.keys() | right.keys()):
            child = f"{path}/{key}"
            result += [child] if key not in left or key not in right else diff_paths(left[key], right[key], child)
        return result
    return [] if left == right else [path]


def grade(plan, expected, previous=None, exact=None, device_ids=()):
    checks = []

    def check(name, ok, detail=None):
        checks.append({"id": name, "passed": bool(ok), **({"detail": detail} if detail else {})})

    statuses = expected.get("status", ["READY"])
    if isinstance(statuses, str):
        statuses = [statuses]
    check("planning_status", plan.get("planningStatus") in statuses)
    doc = definition(plan)
    if plan.get("planningStatus") == "NEEDS_INPUT":
        check("clarification_has_no_actions", not plan.get("actions"))
        if "missingField" in expected:
            missing = (plan.get("clarification") or {}).get("missingFields", [])
            check("meaningful_missing_field", expected["missingField"] in missing)
        if "READY" not in statuses:
            return checks
        # A permissive name-question turn can proceed; required later turns still run.
        return checks
    check("one_dashboard_definition", doc is not None)
    if doc is None:
        return checks
    if expected.get("disclosureAny"):
        visible = (plan.get("explanation") or "").casefold()
        check("alternative_is_disclosed", any(term.casefold() in visible for term in expected["disclosureAny"]))
    check("bounded_widgets", 1 <= len(doc.get("widgets", [])) <= 12)
    if device_ids:
        allowed = set(device_ids)
        scoped = set(doc.get("defaultContext", {}).get("scope", {}).get("deviceIds", []))
        bound = {d for w in doc["widgets"] for d in w.get("binding", {}).get("deviceIds", [])}
        check("authorized_fixture_bindings", bool(scoped) and scoped <= allowed and bound <= allowed)
    if "name" in expected:
        check("exact_name", doc.get("name") == expected["name"])
    for selector in expected.get("roles", []):
        check("role:" + selector, len(matches(doc, selector)) == 1)
    if "onlyRoles" in expected:
        check("only_explicit_roles", all(any(role(widget, selector) for selector in expected["onlyRoles"]) for widget in doc["widgets"]))
    for selector in expected.get("absent", []):
        check("absent:" + selector, not matches(doc, selector))
    for selector, value in expected.get("titles", {}).items():
        widgets = matches(doc, selector)
        check("title:" + selector, len(widgets) == 1 and widgets[0].get("title") == value)
    for selector, values in expected.get("presentation", {}).items():
        widgets = doc["widgets"] if selector == "all" else matches(doc, selector)
        for key, value in values.items():
            default = False if key in ("showTitle", "showDetails") else True
            check(f"presentation:{selector}:{key}", bool(widgets) and all(
                w.get("presentation", {}).get(key, default) == value for w in widgets))
    for selector, view in expected.get("views", {}).items():
        widgets = matches(doc, selector)
        check("view:" + selector, len(widgets) == 1 and
              widgets[0].get("presentation", {}).get("visualization", "list") == view)
    for selector in expected.get("top", []):
        ids = {w["id"] for w in matches(doc, selector)}
        positions = [p for p in doc.get("layouts", {}).get("desktop", []) if p["widgetId"] in ids]
        check("top:" + selector, len(positions) == 1 and positions[0]["y"] == 0)
    for selector, maximum in expected.get("heightMax", {}).items():
        ids = {w["id"] for w in matches(doc, selector)}
        pos = [p for p in doc.get("layouts", {}).get("desktop", []) if p["widgetId"] in ids]
        check("height:" + selector, len(pos) == 1 and pos[0]["h"] <= maximum)
    for selector, period in expected.get("historyPeriods", {}).items():
        widgets = matches(doc, selector)
        actual = widgets[0].get("query", {}).get("time", doc.get("defaultContext", {}).get("time", {})) if len(widgets) == 1 else {}
        check("history_period:" + selector, len(widgets) == 1 and actual.get("preset") == period)
    if "period" in expected:
        check("period", doc.get("defaultContext", {}).get("time", {}).get("preset") == expected["period"])
    if exact is not None:
        check("exact_restored_definition", doc == exact)
    if previous and expected.get("preserve") is not None:
        check_preservation(previous, doc, expected, check)
    if previous and expected.get("unchanged"):
        check("exact_unchanged", previous == doc)
    return checks


def check_preservation(previous, doc, expected, check):
    allowed = expected["preserve"]
    old = {w["id"]: w for w in previous["widgets"]}
    new = {w["id"]: w for w in doc["widgets"]}
    removed = {w["id"] for s in expected.get("absent", []) for w in matches(previous, s)}
    permitted_new = expected.get("allowNewRoles", [])
    check("preserved_widget_ids", set(old) - removed <= set(new))
    check("no_unrequested_new_roles", all(any(role(new[key], s) for s in permitted_new) for key in set(new) - set(old)))
    violations = []
    for key in old.keys() & new.keys():
        selectors = [s for s in allowed if s == "all" or role(old[key], s)]
        paths = [p for s in selectors for p in allowed[s]]
        for path in diff_paths(old[key], new[key]):
            if not any(path == p or path.startswith(p + "/") for p in paths):
                violations.append(key + path)
    check("untargeted_widget_fields", not violations, violations[:20])
    left, right = deepcopy(previous), deepcopy(doc)
    for field in ("widgets", "layouts"):
        left.pop(field, None)
        right.pop(field, None)
    roots = diff_paths(left, right)
    check("untargeted_root_fields", all(p in expected.get("allowRoot", []) for p in roots), roots)
    if not expected.get("allowReflow"):
        check("unchanged_geometry", previous.get("layouts") == doc.get("layouts"))
    else:
        moved = {w["id"] for s in expected.get("top", []) for w in matches(previous, s)} | removed
        for bp in ("desktop", "tablet", "mobile"):
            old_order = [key for key in ordered(previous, bp) if key not in moved]
            new_order = [key for key in ordered(doc, bp) if key in old and key not in moved]
            check("untouched_order:" + bp, old_order == new_order)


def classify(plan, error=None):
    if error:
        if error.status == 500:
            return "APPLICATION_FAILURE"
        if error.code == "MODEL_OUTPUT_LIMIT":
            return "MODEL_CAPACITY_FAILURE"
        return "AVAILABILITY_FAILURE" if error.status in (0, 502, 503, 504) else "REQUEST_REJECTED"
    notes = plan.get("safetyNotes", [])
    if any("Provider request failed" in s or "MODEL_OUTPUT_LIMIT" in s for s in notes):
        return "PROVIDER_FAILURE"
    return "MODEL_RESULT"
