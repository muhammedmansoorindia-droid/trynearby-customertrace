-- Campaigns created by the QA scripts used to carry a QA stamp ("QA-race-40") as their
-- title. Names are now generated from the campaign's own settings, so each campaign is named
-- after the promo code it already owns. The code in promo_codes.code is untouched, so every
-- QR, scan, redemption and approval already recorded against a campaign keeps working: only
-- the human-readable label changes. This rewrites no ids and deletes nothing.
UPDATE customertrace.campaigns c
  JOIN customertrace.promo_codes p ON p.campaign_id = c.id
  SET c.title = p.code
  WHERE c.title <> p.code;

-- A draft has no issued code yet, so it is named by the same shape the server would derive:
-- two placeholder letters plus its target, duration and discount letter.
UPDATE customertrace.campaigns c
  LEFT JOIN customertrace.promo_codes p ON p.campaign_id = c.id
  SET c.title = CONCAT('DR', LPAD(c.target_customers, 2, '0'),
                       LPAD(c.duration_days, 2, '0'),
                       ELT(c.discount_percent - 1, 'B', 'C', 'D', 'E', 'F',
                           'G', 'H', 'I', 'J'))
  WHERE p.id IS NULL
    AND c.title NOT REGEXP '^[A-Z]{2}[0-9]{2}[0-9]{2}[B-J]$';