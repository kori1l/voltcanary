alter table alerts add column if not exists alert_type varchar(100);

update alerts set alert_type = 'UNKNOWN' where alert_type is null;
