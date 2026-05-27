create table ai_model_providers (
  id uuid primary key,
  account_id uuid not null,
  created_by_user_id uuid not null,
  name varchar(120) not null,
  provider_type varchar(64) not null,
  status varchar(32) not null,
  is_default boolean not null default false,
  base_url varchar(512),
  auth_type varchar(32) not null default 'API_KEY',
  masked_secret_hint varchar(80),
  last_test_status varchar(32),
  last_test_message varchar(1024),
  last_test_at timestamptz,
  metadata text not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uq_ai_model_providers_account_name unique (account_id, name)
);

create unique index uq_ai_model_provider_default_per_account
  on ai_model_providers(account_id)
  where is_default = true;

create table ai_model_provider_secrets (
  id uuid primary key,
  provider_id uuid not null references ai_model_providers(id) on delete cascade,
  secret_type varchar(64) not null,
  encrypted_value text not null,
  encryption_key_version varchar(64) not null,
  created_at timestamptz not null,
  rotated_at timestamptz,
  constraint uq_ai_model_provider_secrets_provider_type unique (provider_id, secret_type)
);

create table ai_model_configs (
  id uuid primary key,
  provider_id uuid not null references ai_model_providers(id) on delete cascade,
  display_name varchar(160) not null,
  model_id varchar(255) not null,
  enabled boolean not null default true,
  is_default boolean not null default false,
  temperature numeric(4,3),
  top_p numeric(4,3),
  max_output_tokens integer,
  timeout_ms integer,
  supports_tools boolean not null default false,
  supports_json boolean not null default true,
  response_format varchar(64),
  metadata text not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uq_ai_model_configs_provider_model unique (provider_id, model_id)
);

create unique index uq_ai_model_default_per_provider
  on ai_model_configs(provider_id)
  where is_default = true;

create table ai_agent_model_runs (
  id uuid primary key,
  account_id uuid not null,
  user_id uuid not null,
  plan_id uuid,
  provider_id uuid,
  model_config_id uuid,
  provider_type varchar(64),
  model_id varchar(255),
  status varchar(32) not null,
  prompt_tokens integer,
  completion_tokens integer,
  total_tokens integer,
  latency_ms integer,
  error_code varchar(128),
  error_message varchar(1024),
  created_at timestamptz not null
);

create index idx_ai_model_providers_account on ai_model_providers(account_id);
create index idx_ai_model_configs_provider on ai_model_configs(provider_id);
create index idx_ai_agent_model_runs_account_created on ai_agent_model_runs(account_id, created_at desc);
