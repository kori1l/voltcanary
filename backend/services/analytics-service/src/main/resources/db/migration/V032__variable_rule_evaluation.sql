CREATE TABLE variable_rule_state (
    rule_id UUID NOT NULL,
    source_id UUID NOT NULL,
    last_matched_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY(rule_id,source_id)
);
CREATE TABLE variable_rule_evaluations (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    source_id UUID NOT NULL,
    variable_id UUID NOT NULL,
    variable_revision INTEGER NOT NULL,
    lineage_id UUID NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(event_id,rule_id,rule_version)
);
