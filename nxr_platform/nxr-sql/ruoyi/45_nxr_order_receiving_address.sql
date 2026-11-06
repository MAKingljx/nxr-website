-- Global NXR receiving address shown to partners and customers before they ship cards to NXR.
ALTER TABLE order_admission_config
    ADD COLUMN nxr_receiving_address VARCHAR(2000) NULL AFTER turnaround_text;
