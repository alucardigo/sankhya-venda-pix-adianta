-- ============================================================================
-- VendaPixAdianta - Database Schema Creation Script
-- ============================================================================
-- 
-- This script creates the required database objects for the VendaPixAdianta
-- module, specifically the audit log table AD_LOGVENDAPIXADI.
--
-- ARCHITECTURE PRINCIPLE 4: Observability and Auditing
-- All processing outcomes (success and failure) are logged to this custom
-- audit table for administrative review and debugging.
--
-- Usage:
-- Execute this script in your Sankhya database before deploying the module.
-- ============================================================================

-- Audit Log Table for VendaPixAdianta Module
-- Stores all processing outcomes for PIX sales advance creation
CREATE TABLE AD_LOGVENDAPIXADI (
    -- Primary key: NUNOTA from the original sales order
    NUNOTA NUMERIC(10) NOT NULL,
    
    -- Execution timestamp - when the processing occurred
    DHEXEC TIMESTAMP NOT NULL,
    
    -- Processing status: 'SUCCESS' or 'ERROR'
    STATUS VARCHAR(10) NOT NULL,
    
    -- Error message or success description (max 4000 chars)
    MENSAGEM VARCHAR(4000),
    
    -- Full exception stack trace (only for errors)
    STACKTRACE CLOB,
    
    -- Primary key constraint
    CONSTRAINT PK_AD_LOGVENDAPIXADI PRIMARY KEY (NUNOTA)
);

-- Add comments for documentation
COMMENT ON TABLE AD_LOGVENDAPIXADI IS 'Audit log for VendaPixAdianta module - tracks all advance creation attempts';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.NUNOTA IS 'Sales order number (TGFCAB.NUNOTA)';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.DHEXEC IS 'Execution timestamp';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.STATUS IS 'Processing status: SUCCESS or ERROR';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.MENSAGEM IS 'Result message or error description';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.STACKTRACE IS 'Full exception stack trace (errors only)';

-- Index for performance when querying by status
CREATE INDEX IX_AD_LOGVENDAPIXADI_STATUS ON AD_LOGVENDAPIXADI (STATUS);

-- Index for performance when querying by execution date
CREATE INDEX IX_AD_LOGVENDAPIXADI_DHEXEC ON AD_LOGVENDAPIXADI (DHEXEC);

-- ============================================================================
-- VERIFICATION QUERIES
-- ============================================================================
-- Use these queries to verify the table was created correctly and monitor
-- the module's execution:

-- Verify table structure
-- SELECT * FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'AD_LOGVENDAPIXADI' ORDER BY COLUMN_ID;

-- Check processing statistics
-- SELECT STATUS, COUNT(*) as TOTAL FROM AD_LOGVENDAPIXADI GROUP BY STATUS;

-- View recent processing attempts
-- SELECT NUNOTA, DHEXEC, STATUS, MENSAGEM 
-- FROM AD_LOGVENDAPIXADI 
-- ORDER BY DHEXEC DESC 
-- FETCH FIRST 10 ROWS ONLY;

-- Find recent errors
-- SELECT NUNOTA, DHEXEC, MENSAGEM, STACKTRACE 
-- FROM AD_LOGVENDAPIXADI 
-- WHERE STATUS = 'ERROR' 
-- ORDER BY DHEXEC DESC 
-- FETCH FIRST 5 ROWS ONLY;

-- ============================================================================
-- MAINTENANCE QUERIES
-- ============================================================================

-- Clean up old audit logs (optional - run periodically)
-- Keep only logs from the last 90 days
-- DELETE FROM AD_LOGVENDAPIXADI 
-- WHERE DHEXEC < SYSDATE - 90;

-- View processing success rate
-- SELECT 
--     COUNT(*) as TOTAL_EXECUTIONS,
--     COUNT(CASE WHEN STATUS = 'SUCCESS' THEN 1 END) as SUCCESSFUL,
--     COUNT(CASE WHEN STATUS = 'ERROR' THEN 1 END) as ERRORS,
--     ROUND(COUNT(CASE WHEN STATUS = 'SUCCESS' THEN 1 END) * 100.0 / COUNT(*), 2) as SUCCESS_RATE_PERCENT
-- FROM AD_LOGVENDAPIXADI;

-- ============================================================================