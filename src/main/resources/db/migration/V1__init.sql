CREATE TABLE IF NOT EXISTS users (
  "User_ID" TEXT PRIMARY KEY,
  "First_Name" TEXT NOT NULL,
  "Last_Name" TEXT NOT NULL,
  "Email" TEXT UNIQUE NOT NULL,
  "Role" TEXT NOT NULL,
  "Status" TEXT NOT NULL,
  "Password" TEXT
);

CREATE TABLE IF NOT EXISTS products (
  "Product_ID" TEXT PRIMARY KEY,
  "Name" TEXT NOT NULL,
  "SKU_Code" TEXT UNIQUE NOT NULL,
  "Type" TEXT NOT NULL,
  "Unit_Price" DOUBLE PRECISION NOT NULL,
  "Description" TEXT NOT NULL,
  "Is_Active" BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS pipelines (
  "Pipeline_ID" TEXT PRIMARY KEY,
  "Pipeline_Name" TEXT NOT NULL,
  "Stages_JSON" JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS contacts (
  "Contact_ID" TEXT PRIMARY KEY,
  "First_Name" TEXT NOT NULL,
  "Last_Name" TEXT NOT NULL,
  "Email" TEXT NOT NULL,
  "Phone_Primary" TEXT NOT NULL,
  "Organization_Name" TEXT NOT NULL,
  "Contact_Type" TEXT NOT NULL,
  "Source" TEXT NOT NULL,
  "Created_Date" TEXT NOT NULL,
  "Owner_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS deals (
  "Deal_ID" TEXT PRIMARY KEY,
  "Deal_Name" TEXT NOT NULL,
  "Associated_Contact_ID" TEXT NOT NULL REFERENCES contacts("Contact_ID") ON DELETE RESTRICT,
  "Pipeline_ID" TEXT NOT NULL REFERENCES pipelines("Pipeline_ID") ON DELETE RESTRICT,
  "Current_Stage" TEXT NOT NULL,
  "Deal_Value" DOUBLE PRECISION NOT NULL,
  "Expected_Close_Date" TEXT NOT NULL,
  "Probability_Percentage" INTEGER NOT NULL,
  "Win_Loss_Reason" TEXT,
  "Owner_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE RESTRICT,
  "Status" TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS line_items (
  "Line_Item_ID" TEXT PRIMARY KEY,
  "Deal_ID" TEXT NOT NULL REFERENCES deals("Deal_ID") ON DELETE CASCADE,
  "Product_ID" TEXT NOT NULL REFERENCES products("Product_ID") ON DELETE RESTRICT,
  "Quantity" INTEGER NOT NULL,
  "Unit_Price_At_Sale" DOUBLE PRECISION NOT NULL,
  "Discount_Amount" DOUBLE PRECISION NOT NULL,
  "Total_Line_Value" DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS deal_contacts (
  "Deal_ID" TEXT NOT NULL REFERENCES deals("Deal_ID") ON DELETE CASCADE,
  "Contact_ID" TEXT NOT NULL REFERENCES contacts("Contact_ID") ON DELETE CASCADE,
  "Is_Primary" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("Deal_ID", "Contact_ID")
);

CREATE TABLE IF NOT EXISTS activities (
  "Log_ID" TEXT PRIMARY KEY,
  "Parent_Object_Type" TEXT NOT NULL,
  "Parent_Object_ID" TEXT NOT NULL,
  "Activity_Type" TEXT NOT NULL,
  "Summary" TEXT NOT NULL,
  "Detailed_Description" TEXT NOT NULL,
  "Attachment_URL" TEXT,
  "Follow_Up_Date" TEXT,
  "Log_Date" TEXT NOT NULL,
  "Logged_By_User_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS activity_attachments (
  "Attachment_ID" TEXT PRIMARY KEY,
  "Log_ID" TEXT NOT NULL REFERENCES activities("Log_ID") ON DELETE CASCADE,
  "File_Name" TEXT NOT NULL,
  "Mime_Type" TEXT NOT NULL,
  "File_Size" INTEGER NOT NULL,
  "Storage_Path" TEXT NOT NULL,
  "Uploaded_By_User_ID" TEXT NOT NULL,
  "Uploaded_At" TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_att_log ON activity_attachments("Log_ID");
