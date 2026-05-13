create table accounts (
    id uuid primary key,
    name varchar(255) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table locations (
    id uuid primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    name varchar(255) not null,
    description text,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_locations_account_id on locations (account_id);

create table device_types (
    id uuid primary key,
    code varchar(100) not null unique,
    display_name varchar(255) not null
);

create table devices (
    id uuid primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    location_id uuid references locations (id) on delete set null,
    device_type_id uuid not null references device_types (id),
    name varchar(255) not null,
    external_id varchar(255),
    status varchar(50) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_devices_account_id on devices (account_id);
create index idx_devices_location_id on devices (location_id);
create index idx_devices_device_type_id on devices (device_type_id);
