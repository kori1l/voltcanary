alter table anomalies add column if not exists type varchar(100);

update anomalies set type = 'UNKNOWN' where type is null;
