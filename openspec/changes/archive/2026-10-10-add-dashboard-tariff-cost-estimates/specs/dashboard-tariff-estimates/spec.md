## ADDED Requirements

### Requirement: Effective versioned tariff access

Tariffs SHALL be versioned by effective interval and currency under independent read/write grants, use decimal rates, and expose explicit taxes and fixed-fee policies without granting access through dashboard edit permission.

#### Scenario: Tariff hidden from editor

- **GIVEN** a user can edit a dashboard but lacks tariff.read
- **WHEN** cost is requested
- **THEN** cost and protected tariff values remain unavailable even though energy may be readable.

#### Scenario: Rate changes within interval

- **GIVEN** a tariff revision becomes effective halfway through the requested period
- **WHEN** cost is calculated
- **THEN** energy segments use the appropriate effective versions and provenance identifies them.

### Requirement: Cost is an evidence-qualified estimate

Cost widgets SHALL be estimates derived from qualified energy and effective tariffs, preserve missing coverage, reject implicit currency conversion and SHALL NOT imply an invoice or demonstrated savings.

#### Scenario: Tariff or currency mismatch

- **GIVEN** energy exists but part of its period has no tariff or comparison uses another currency
- **WHEN** W14 is queried
- **THEN** missing parts remain partial/unavailable and comparison is not presented as financial savings.

#### Scenario: Complete flat-rate evidence

- **GIVEN** qualified energy and one effective rate cover the full interval
- **WHEN** the widget is rendered
- **THEN** the decimal estimate shows its currency and a compact estimate indicator; authorized tariff version, energy method and taxes/fees policy remain available in panel information and appear inline only when the relevant annotations are enabled.
