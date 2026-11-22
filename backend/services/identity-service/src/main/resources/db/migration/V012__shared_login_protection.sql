create table login_protection_guard (id integer primary key);
insert into login_protection_guard(id) values (1);

create table login_protection_keys (
    key_hash varchar(64) primary key,
    attempts text not null,
    failures integer not null,
    locked_until timestamp,
    expires_at timestamp not null
);
create index login_protection_expiry on login_protection_keys(expires_at);
