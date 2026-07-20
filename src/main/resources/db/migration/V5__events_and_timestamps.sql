-- ==========================================
-- 1. TIMESTAMPS AUTOMATION (ENH-008)
-- ==========================================

-- Trigger function to automatically update the "Updated_At" column on modification
CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW."Updated_At" = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Add Created_At and Updated_At columns to Users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS "Created_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE users ADD COLUMN IF NOT EXISTS "Updated_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
DROP TRIGGER IF EXISTS update_users_modtime ON users;
CREATE TRIGGER update_users_modtime BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION update_modified_column();

-- Add Created_At and Updated_At columns to Deals table
ALTER TABLE deals ADD COLUMN IF NOT EXISTS "Created_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE deals ADD COLUMN IF NOT EXISTS "Updated_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
DROP TRIGGER IF EXISTS update_deals_modtime ON deals;
CREATE TRIGGER update_deals_modtime BEFORE UPDATE ON deals FOR EACH ROW EXECUTE FUNCTION update_modified_column();

-- Add Created_At and Updated_At columns to Contacts table
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS "Created_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS "Updated_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
DROP TRIGGER IF EXISTS update_contacts_modtime ON contacts;
CREATE TRIGGER update_contacts_modtime BEFORE UPDATE ON contacts FOR EACH ROW EXECUTE FUNCTION update_modified_column();

-- Add Created_At and Updated_At columns to Activities table (representing Notes & Tasks)
ALTER TABLE activities ADD COLUMN IF NOT EXISTS "Created_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE activities ADD COLUMN IF NOT EXISTS "Updated_At" TIMESTAMPTZ NOT NULL DEFAULT NOW();
DROP TRIGGER IF EXISTS update_activities_modtime ON activities;
CREATE TRIGGER update_activities_modtime BEFORE UPDATE ON activities FOR EACH ROW EXECUTE FUNCTION update_modified_column();


-- ==========================================
-- 2. CLOSEMORE EVENTS LOG TABLE
-- ==========================================

CREATE TABLE IF NOT EXISTS events_log (
  "Log_Entry_ID" SERIAL PRIMARY KEY,
  "Timestamp" TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  "User_ID" TEXT NOT NULL,
  "User_Name" TEXT NOT NULL,
  "Action_Type" TEXT NOT NULL,   -- e.g. 'DEAL_CREATED', 'STAGE_CHANGE', 'NOTE_ADDED', 'CONTACT_EDITED', 'DEAL_CLOSED', 'TASK_COMPLETED', 'USER_LOGIN', 'USER_LOGOUT'
  "Object_Type" TEXT NOT NULL,   -- e.g. 'Deal', 'Contact', 'Task', 'Note', 'User'
  "Object_ID" TEXT NOT NULL,
  "Object_Name" TEXT NOT NULL,
  "Before_State" TEXT,
  "After_State" TEXT
);

CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events_log("Timestamp" DESC);
