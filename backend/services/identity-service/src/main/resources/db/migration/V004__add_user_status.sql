alter table users add column status varchar(50) not null default 'ACTIVE';

alter table users alter column status drop default;
