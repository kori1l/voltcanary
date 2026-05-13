create table users (
    id uuid primary key,
    email varchar(255) not null unique,
    password_hash varchar(255) not null,
    role varchar(50) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);
