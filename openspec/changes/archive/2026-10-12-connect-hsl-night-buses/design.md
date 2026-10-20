# Design

Allow only tram and bus ongoing VP topics. Legacy bindings without mode remain tram. Bus source IDs use hsl-bus-operator-vehicle; mode is part of connector mailbox identity so identical numerical tram/bus keys cannot cross-bind. Explicit allowlists, freshness, authenticated ingress, sampled latest mailboxes and no fabricated values remain unchanged.

Discovery can select a mode and route allowlist, using fresh non-retained GPS only. Provisioning preserves previous vehicles and credentials, matches devices by provider/mode/external identity, refuses shrink or fleets above twenty, and publishes one mixed fleet widget. BusCount is a desired bus count, Count retains its tram meaning. Repeat runs reuse bindings without duplication. Marker size remains 32px with an 18px upright bus glyph.

Verify pure bus mapping and mode isolation, legacy configuration compatibility and focused frontend checks, then deploy and verify real latest/history transport context, advancing timestamps and the published map. Silent trams remain STALE; they must not block nighttime bus verification.
