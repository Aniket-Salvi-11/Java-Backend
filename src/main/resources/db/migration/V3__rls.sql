-- Drop existing policies if they exist to allow clean re-runs/migrations
DROP POLICY IF EXISTS contacts_rls_policy ON contacts;
DROP POLICY IF EXISTS deals_rls_policy ON deals;
DROP POLICY IF EXISTS line_items_rls_policy ON line_items;
DROP POLICY IF EXISTS deal_contacts_rls_policy ON deal_contacts;
DROP POLICY IF EXISTS activities_rls_policy ON activities;
DROP POLICY IF EXISTS activity_attachments_rls_policy ON activity_attachments;

-- 1. Enable RLS and Force RLS on contacts
ALTER TABLE contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE contacts FORCE ROW LEVEL SECURITY;

CREATE POLICY contacts_rls_policy ON contacts
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR "Owner_ID" = current_setting('app.current_user_id', true)
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR "Owner_ID" = current_setting('app.current_user_id', true)
  );

-- 2. Enable RLS and Force RLS on deals
ALTER TABLE deals ENABLE ROW LEVEL SECURITY;
ALTER TABLE deals FORCE ROW LEVEL SECURITY;

CREATE POLICY deals_rls_policy ON deals
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR "Owner_ID" = current_setting('app.current_user_id', true)
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR "Owner_ID" = current_setting('app.current_user_id', true)
  );

-- 3. Enable RLS and Force RLS on line_items
ALTER TABLE line_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE line_items FORCE ROW LEVEL SECURITY;

CREATE POLICY line_items_rls_policy ON line_items
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR EXISTS (
      SELECT 1 FROM deals d
      WHERE d."Deal_ID" = line_items."Deal_ID"
        AND d."Owner_ID" = current_setting('app.current_user_id', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR EXISTS (
      SELECT 1 FROM deals d
      WHERE d."Deal_ID" = line_items."Deal_ID"
        AND d."Owner_ID" = current_setting('app.current_user_id', true)
    )
  );

-- 4. Enable RLS and Force RLS on deal_contacts
ALTER TABLE deal_contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE deal_contacts FORCE ROW LEVEL SECURITY;

CREATE POLICY deal_contacts_rls_policy ON deal_contacts
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR EXISTS (
      SELECT 1 FROM deals d
      WHERE d."Deal_ID" = deal_contacts."Deal_ID"
        AND d."Owner_ID" = current_setting('app.current_user_id', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR EXISTS (
      SELECT 1 FROM deals d
      WHERE d."Deal_ID" = deal_contacts."Deal_ID"
        AND d."Owner_ID" = current_setting('app.current_user_id', true)
    )
  );

-- 5. Enable RLS and Force RLS on activities
ALTER TABLE activities ENABLE ROW LEVEL SECURITY;
ALTER TABLE activities FORCE ROW LEVEL SECURITY;

CREATE POLICY activities_rls_policy ON activities
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR "Logged_By_User_ID" = current_setting('app.current_user_id', true)
    OR (
      "Parent_Object_Type" = 'Deal' AND EXISTS (
        SELECT 1 FROM deals d
        WHERE d."Deal_ID" = activities."Parent_Object_ID"
          AND d."Owner_ID" = current_setting('app.current_user_id', true)
      )
    )
    OR (
      "Parent_Object_Type" = 'Contact' AND EXISTS (
        SELECT 1 FROM contacts c
        WHERE c."Contact_ID" = activities."Parent_Object_ID"
          AND c."Owner_ID" = current_setting('app.current_user_id', true)
      )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR "Logged_By_User_ID" = current_setting('app.current_user_id', true)
    OR (
      "Parent_Object_Type" = 'Deal' AND EXISTS (
        SELECT 1 FROM deals d
        WHERE d."Deal_ID" = activities."Parent_Object_ID"
          AND d."Owner_ID" = current_setting('app.current_user_id', true)
      )
    )
    OR (
      "Parent_Object_Type" = 'Contact' AND EXISTS (
        SELECT 1 FROM contacts c
        WHERE c."Contact_ID" = activities."Parent_Object_ID"
          AND c."Owner_ID" = current_setting('app.current_user_id', true)
      )
    )
  );

-- 6. Enable RLS and Force RLS on activity_attachments
ALTER TABLE activity_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity_attachments FORCE ROW LEVEL SECURITY;

CREATE POLICY activity_attachments_rls_policy ON activity_attachments
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR EXISTS (
      SELECT 1 FROM activities a
      WHERE a."Log_ID" = activity_attachments."Log_ID"
        AND (
          a."Logged_By_User_ID" = current_setting('app.current_user_id', true)
          OR (
            a."Parent_Object_Type" = 'Deal' AND EXISTS (
              SELECT 1 FROM deals d
              WHERE d."Deal_ID" = a."Parent_Object_ID"
                AND d."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
          OR (
            a."Parent_Object_Type" = 'Contact' AND EXISTS (
              SELECT 1 FROM contacts c
              WHERE c."Contact_ID" = a."Parent_Object_ID"
                AND c."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
        )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
    OR EXISTS (
      SELECT 1 FROM activities a
      WHERE a."Log_ID" = activity_attachments."Log_ID"
        AND (
          a."Logged_By_User_ID" = current_setting('app.current_user_id', true)
          OR (
            a."Parent_Object_Type" = 'Deal' AND EXISTS (
              SELECT 1 FROM deals d
              WHERE d."Deal_ID" = a."Parent_Object_ID"
                AND d."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
          OR (
            a."Parent_Object_Type" = 'Contact' AND EXISTS (
              SELECT 1 FROM contacts c
              WHERE c."Contact_ID" = a."Parent_Object_ID"
                AND c."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
        )
    )
  );
