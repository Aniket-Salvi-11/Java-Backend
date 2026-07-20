-- Drop old policies to replace them with tenant-level (company-level) policies
DROP POLICY IF EXISTS contacts_rls_policy ON contacts;
DROP POLICY IF EXISTS deals_rls_policy ON deals;
DROP POLICY IF EXISTS line_items_rls_policy ON line_items;
DROP POLICY IF EXISTS deal_contacts_rls_policy ON deal_contacts;
DROP POLICY IF EXISTS activities_rls_policy ON activities;
DROP POLICY IF EXISTS activity_attachments_rls_policy ON activity_attachments;
DROP POLICY IF EXISTS users_rls_policy ON users;

-- 1. Enable and Force RLS on users table (to isolate user directory)
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;

CREATE POLICY users_rls_policy ON users
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR "Organization_Name" = current_setting('app.current_user_tenant', true)
    OR current_setting('app.current_user_tenant', true) IS NULL
    OR current_setting('app.current_user_tenant', true) = ''
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR "Organization_Name" = current_setting('app.current_user_tenant', true)
    OR current_setting('app.current_user_tenant', true) IS NULL
    OR current_setting('app.current_user_tenant', true) = ''
  );

-- 2. Refactor contacts policy for tenant-level RLS
CREATE POLICY contacts_rls_policy ON contacts
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users owner_user
      WHERE owner_user."User_ID" = contacts."Owner_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR contacts."Owner_ID" = current_setting('app.current_user_id', true)
        )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users owner_user
      WHERE owner_user."User_ID" = contacts."Owner_ID"
        AND owner_user."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR contacts."Owner_ID" = current_setting('app.current_user_id', true)
        )
    )
  );

-- 3. Refactor deals policy for tenant-level RLS
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
        )
    )
  );

-- 4. Refactor line_items policy for tenant-level RLS
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
        )
    )
  );

-- 5. Refactor deal_contacts policy for tenant-level RLS
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
        )
    )
  );

-- 6. Refactor activities policy for tenant-level RLS
CREATE POLICY activities_rls_policy ON activities
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users logger
      WHERE logger."User_ID" = activities."Logged_By_User_ID"
        AND logger."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR activities."Logged_By_User_ID" = current_setting('app.current_user_id', true)
          OR (
            activities."Parent_Object_Type" = 'Deal' AND EXISTS (
              SELECT 1 FROM deals d
              WHERE d."Deal_ID" = activities."Parent_Object_ID"
                AND d."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
          OR (
            activities."Parent_Object_Type" = 'Contact' AND EXISTS (
              SELECT 1 FROM contacts c
              WHERE c."Contact_ID" = activities."Parent_Object_ID"
                AND c."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
        )
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users logger
      WHERE logger."User_ID" = activities."Logged_By_User_ID"
        AND logger."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR activities."Logged_By_User_ID" = current_setting('app.current_user_id', true)
          OR (
            activities."Parent_Object_Type" = 'Deal' AND EXISTS (
              SELECT 1 FROM deals d
              WHERE d."Deal_ID" = activities."Parent_Object_ID"
                AND d."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
          OR (
            activities."Parent_Object_Type" = 'Contact' AND EXISTS (
              SELECT 1 FROM contacts c
              WHERE c."Contact_ID" = activities."Parent_Object_ID"
                AND c."Owner_ID" = current_setting('app.current_user_id', true)
            )
          )
        )
    )
  );

-- 7. Refactor activity_attachments policy for tenant-level RLS
CREATE POLICY activity_attachments_rls_policy ON activity_attachments
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM activities a
      JOIN users logger ON logger."User_ID" = a."Logged_By_User_ID"
      WHERE a."Log_ID" = activity_attachments."Log_ID"
        AND logger."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR a."Logged_By_User_ID" = current_setting('app.current_user_id', true)
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
    OR EXISTS (
      SELECT 1 FROM activities a
      JOIN users logger ON logger."User_ID" = a."Logged_By_User_ID"
      WHERE a."Log_ID" = activity_attachments."Log_ID"
        AND logger."Organization_Name" = current_setting('app.current_user_tenant', true)
        AND (
          current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
          OR a."Logged_By_User_ID" = current_setting('app.current_user_id', true)
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
