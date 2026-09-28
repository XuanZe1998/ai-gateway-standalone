-- Reserve a high-ID range for locally provisioned CAS users; never claim existing platform IDs.
CREATE SEQUENCE IF NOT EXISTS campus_system_user_id_seq
    AS BIGINT START WITH 2000000000 INCREMENT BY 1 NO CYCLE;
