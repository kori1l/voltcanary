alter table accounts add column user_id uuid unique;

alter table devices add column manufacturer varchar(255);
alter table devices add column model varchar(255);

insert into device_types (id, code, display_name) values
    ('00000000-0000-0000-0000-000000000001', 'GENERIC_APPLIANCE', 'Generic appliance'),
    ('00000000-0000-0000-0000-000000000002', 'AIR_CONDITIONER', 'Air conditioner'),
    ('00000000-0000-0000-0000-000000000003', 'FRIDGE', 'Fridge'),
    ('00000000-0000-0000-0000-000000000004', 'BOILER', 'Boiler'),
    ('00000000-0000-0000-0000-000000000005', 'PUMP', 'Pump'),
    ('00000000-0000-0000-0000-000000000006', 'HEATER', 'Heater'),
    ('00000000-0000-0000-0000-000000000007', 'WASHING_MACHINE', 'Washing machine'),
    ('00000000-0000-0000-0000-000000000008', 'SERVER_NAS', 'Server/NAS'),
    ('00000000-0000-0000-0000-000000000009', 'THREE_D_PRINTER', '3D printer'),
    ('00000000-0000-0000-0000-000000000010', 'CUSTOM', 'Custom');
