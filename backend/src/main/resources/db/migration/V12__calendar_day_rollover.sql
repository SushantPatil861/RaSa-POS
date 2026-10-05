-- V12: the trading day is the plain calendar day in the business time zone (rolls over at midnight),
-- so every screen agrees with the user's clock. (It was 04:00 by default, which made "today" differ
-- between the Home page and the sale form between midnight and 4 AM.) Existing records are unaffected:
-- they carry an explicit txn_date.
update business set day_rollover_hour = 0 where id = 1;

-- Calendar-date column defaults must follow the business time zone, not the database server's (UTC in Docker).
alter table hygiene_checklists alter column start_date set default ((now() at time zone 'Asia/Kolkata')::date);
alter table temperature_points alter column start_date set default ((now() at time zone 'Asia/Kolkata')::date);
