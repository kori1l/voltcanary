create table scheduler_locks (
    lock_name varchar(255) primary key,
    locked_until timestamp not null,
    locked_at timestamp not null,
    locked_by varchar(255) not null
);
