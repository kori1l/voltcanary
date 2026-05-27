alter table locations add column if not exists parent_id uuid references locations (id) on delete set null;
alter table locations add column if not exists building varchar(255);
alter table locations add column if not exists floor varchar(100);
alter table locations add column if not exists room varchar(255);
alter table locations add column if not exists zone varchar(255);
alter table locations add column if not exists tags_json text;

create index if not exists idx_locations_parent_id on locations (parent_id);
create index if not exists idx_locations_building on locations (building);
create index if not exists idx_locations_zone on locations (zone);
