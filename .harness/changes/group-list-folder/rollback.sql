ALTER TABLE group_link DROP INDEX idx_group_link_folder, DROP COLUMN folder_id;
DROP TABLE IF EXISTS group_folder;
