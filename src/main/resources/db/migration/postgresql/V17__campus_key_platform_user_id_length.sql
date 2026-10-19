-- CAS user IDs contain a namespace plus a full SHA-256 digest (68 characters).
-- Match sldd_system_users.user_id without changing existing billing identities.
ALTER TABLE campus_gateway_key
    ALTER COLUMN platform_user_id TYPE VARCHAR(255);
