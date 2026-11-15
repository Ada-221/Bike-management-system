-- Counters used by the application to build prefixed IDs, e.g. veh_001, rnt_001.
-- The Java IdGenerator formats them (prefix + zero-padded number).
CREATE SEQUENCE vehicle_id_seq     START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE rental_id_seq      START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE maintenance_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE event_id_seq       START WITH 1 INCREMENT BY 1;
