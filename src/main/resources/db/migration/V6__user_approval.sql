-- Registration approval workflow: privileged roles (Admin, Executive/Manager)
-- may be stored as Status = 'Pending_Approval' until an active Admin approves.

COMMENT ON COLUMN users."Status" IS 'Active | Inactive | Pending_Approval';
