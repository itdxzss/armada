-- Run only after confirming no application node still references this column.
ALTER TABLE account_state DROP COLUMN manual_restriction_cleared_at;
