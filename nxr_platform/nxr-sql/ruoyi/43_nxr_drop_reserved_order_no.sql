-- Order numbers are now stored directly in agent_intake.intake_no; the reserve column is no longer used.
ALTER TABLE agent_intake
    DROP INDEX uk_agent_intake_reserved_order_no,
    DROP COLUMN reserved_order_no;
