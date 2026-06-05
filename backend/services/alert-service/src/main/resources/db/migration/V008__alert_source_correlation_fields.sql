alter table alerts
    add column if not exists risk_score_id uuid;

alter table alerts
    add column if not exists rule_id varchar(255);

alter table alerts
    add column if not exists calculation_window_start timestamp with time zone;

alter table alerts
    add column if not exists calculation_window_end timestamp with time zone;

alter table alerts
    add column if not exists calculation_input_hash varchar(255);
