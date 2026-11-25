-- Order list resolves each order's creator from its FNB_ORDER_CREATED audit row (TASK-087).
CREATE INDEX idx_audit_logs_tenant_entity_action ON audit_logs (tenant_id, entity_id, action);
