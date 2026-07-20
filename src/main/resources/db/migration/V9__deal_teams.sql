-- Create Deal Team Members Table
CREATE TABLE IF NOT EXISTS deal_team_members (
  "Deal_ID" TEXT NOT NULL REFERENCES deals("Deal_ID") ON DELETE CASCADE,
  "User_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  PRIMARY KEY ("Deal_ID", "User_ID")
);

-- Enable RLS
ALTER TABLE deal_team_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE deal_team_members FORCE ROW LEVEL SECURITY;

-- Drop existing tenant-level RLS policies to recreate them with team member support
DROP POLICY IF EXISTS deals_rls_policy ON deals;
DROP POLICY IF EXISTS line_items_rls_policy ON line_items;
DROP POLICY IF EXISTS deal_contacts_rls_policy ON deal_contacts;
DROP POLICY IF EXISTS deal_team_members_rls_policy ON deal_team_members;

-- RLS Policy for deal_team_members
CREATE POLICY deal_team_members_rls_policy ON deal_team_members
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users u
      WHERE u."User_ID" = deal_team_members."User_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users u
      WHERE u."User_ID" = deal_team_members."User_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );

-- RLS Policy for deals
CREATE POLICY deals_rls_policy ON deals
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users owner_user
      WHERE owner_user."User_ID" = deals."Owner_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR deals."Owner_ID" = current_setting('app.current_user_id', true)
          OR EXISTS (
            SELECT 1 FROM deal_team_members dtm
            WHERE dtm."Deal_ID" = deals."Deal_ID"
              AND dtm."User_ID" = current_setting('app.current_user_id', true)
          )
        )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users owner_user
      WHERE owner_user."User_ID" = deals."Owner_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR deals."Owner_ID" = current_setting('app.current_user_id', true)
          OR EXISTS (
            SELECT 1 FROM deal_team_members dtm
            WHERE dtm."Deal_ID" = deals."Deal_ID"
              AND dtm."User_ID" = current_setting('app.current_user_id', true)
          )
        )
    )
  );

-- RLS Policy for line_items
CREATE POLICY line_items_rls_policy ON line_items
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM deals d
      JOIN users owner_user ON owner_user."User_ID" = d."Owner_ID"
      WHERE d."Deal_ID" = line_items."Deal_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR d."Owner_ID" = current_setting('app.current_user_id', true)
          OR EXISTS (
            SELECT 1 FROM deal_team_members dtm
            WHERE dtm."Deal_ID" = d."Deal_ID"
              AND dtm."User_ID" = current_setting('app.current_user_id', true)
          )
        )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM deals d
      JOIN users owner_user ON owner_user."User_ID" = d."Owner_ID"
      WHERE d."Deal_ID" = line_items."Deal_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR d."Owner_ID" = current_setting('app.current_user_id', true)
          OR EXISTS (
            SELECT 1 FROM deal_team_members dtm
            WHERE dtm."Deal_ID" = d."Deal_ID"
              AND dtm."User_ID" = current_setting('app.current_user_id', true)
          )
        )
    )
  );

-- RLS Policy for deal_contacts
CREATE POLICY deal_contacts_rls_policy ON deal_contacts
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM deals d
      JOIN users owner_user ON owner_user."User_ID" = d."Owner_ID"
      WHERE d."Deal_ID" = deal_contacts."Deal_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR d."Owner_ID" = current_setting('app.current_user_id', true)
          OR EXISTS (
            SELECT 1 FROM deal_team_members dtm
            WHERE dtm."Deal_ID" = d."Deal_ID"
              AND dtm."User_ID" = current_setting('app.current_user_id', true)
          )
        )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM deals d
      JOIN users owner_user ON owner_user."User_ID" = d."Owner_ID"
      WHERE d."Deal_ID" = deal_contacts."Deal_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR d."Owner_ID" = current_setting('app.current_user_id', true)
          OR EXISTS (
            SELECT 1 FROM deal_team_members dtm
            WHERE dtm."Deal_ID" = d."Deal_ID"
              AND dtm."User_ID" = current_setting('app.current_user_id', true)
          )
        )
    )
  );
