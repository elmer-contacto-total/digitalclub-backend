-- Add s3_key column to app_versions for S3-uploaded installers
ALTER TABLE app_versions ADD COLUMN s3_key VARCHAR(500);
