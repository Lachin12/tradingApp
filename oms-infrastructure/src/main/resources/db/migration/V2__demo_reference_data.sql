-- Local portfolio demonstration data only. This is not an account provisioning API.
INSERT INTO accounts(id, name, active)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo trading account', TRUE);

INSERT INTO instruments(id, symbol, currency, tick_size, quantity_step)
VALUES ('00000000-0000-0000-0000-000000000002', 'DEMO', 'USD', 0.01, 1);
