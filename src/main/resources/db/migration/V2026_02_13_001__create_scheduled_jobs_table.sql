CREATE TABLE IF NOT EXISTS scheduled_jobs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(255) NOT NULL,
    job_type VARCHAR(255) NOT NULL,
    job_data TEXT,
    execute_at TIMESTAMP NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_at TIMESTAMP,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS index_scheduled_jobs_on_status ON scheduled_jobs(status);
CREATE INDEX IF NOT EXISTS index_scheduled_jobs_on_execute_at ON scheduled_jobs(execute_at);
CREATE INDEX IF NOT EXISTS index_scheduled_jobs_on_job_type ON scheduled_jobs(job_type);
