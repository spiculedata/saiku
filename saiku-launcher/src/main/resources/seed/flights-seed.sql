-- Flights demo fixture — trimmed BTS-shaped data for flights.ossie.yaml.
-- 4 tables: FLIGHT (fact) + CARRIER, AIRPORT, AIRCRAFT (dims). ~60 flights
-- across 8 airports, 4 carriers, 6 tail numbers.

DROP TABLE IF EXISTS FLIGHT;
DROP TABLE IF EXISTS CARRIER;
DROP TABLE IF EXISTS AIRPORT;
DROP TABLE IF EXISTS AIRCRAFT;

CREATE TABLE CARRIER (
  CARRIER_ID    INT PRIMARY KEY,
  IATA_CODE     VARCHAR(3),
  CARRIER_NAME  VARCHAR(64),
  HUB_STATE     VARCHAR(2)
);

INSERT INTO CARRIER VALUES
 (1, 'AA', 'American Airlines', 'TX'),
 (2, 'DL', 'Delta Air Lines',   'GA'),
 (3, 'UA', 'United Airlines',   'IL'),
 (4, 'WN', 'Southwest Airlines','TX');

CREATE TABLE AIRPORT (
  AIRPORT_ID    INT PRIMARY KEY,
  AIRPORT_CODE  VARCHAR(4),
  AIRPORT_NAME  VARCHAR(64),
  AIRPORT_CITY  VARCHAR(48),
  AIRPORT_STATE VARCHAR(2)
);

INSERT INTO AIRPORT VALUES
 (1, 'JFK', 'John F. Kennedy Intl', 'New York',      'NY'),
 (2, 'LAX', 'Los Angeles Intl',     'Los Angeles',   'CA'),
 (3, 'ORD', 'O''Hare Intl',         'Chicago',       'IL'),
 (4, 'ATL', 'Hartsfield-Jackson',   'Atlanta',       'GA'),
 (5, 'DFW', 'Dallas Fort Worth',    'Dallas',        'TX'),
 (6, 'SEA', 'Seattle-Tacoma Intl',  'Seattle',       'WA'),
 (7, 'SFO', 'San Francisco Intl',   'San Francisco', 'CA'),
 (8, 'MIA', 'Miami Intl',           'Miami',         'FL');

CREATE TABLE AIRCRAFT (
  TAIL_NUM        VARCHAR(8) PRIMARY KEY,
  MANUFACTURER    VARCHAR(32),
  MODEL           VARCHAR(32),
  NUMBER_OF_SEATS INT,
  YEAR_MFR        INT
);

INSERT INTO AIRCRAFT VALUES
 ('N101AA', 'Boeing',      '737-800',  160, 2015),
 ('N202DL', 'Airbus',      'A320',     150, 2017),
 ('N303UA', 'Boeing',      '777-300',  365, 2018),
 ('N404WN', 'Boeing',      '737-700',  143, 2013),
 ('N505AA', 'Boeing',      '787-9',    290, 2020),
 ('N606DL', 'Airbus',      'A321neo',  196, 2021);

CREATE TABLE FLIGHT (
  FLIGHT_ID              INT PRIMARY KEY,
  CARRIER_ID             INT,
  TAIL_NUM               VARCHAR(8),
  ORIGIN_AIRPORT_ID      INT,
  DEST_AIRPORT_ID        INT,
  SCHEDULED_DATE_MONTH   VARCHAR(7),   -- YYYY-MM for a natural time axis
  DEP_DELAY_MIN          INT,
  ARR_DELAY_MIN          INT,
  CANCELLED              INT,          -- 0 / 1
  DISTANCE_MILES         INT
);

-- Six months of flight activity, ~10 flights per month. Delays trend upward
-- Nov/Dec to give the anomaly / forecast endpoints a real signal.
INSERT INTO FLIGHT VALUES
 -- July
 ( 1, 1, 'N101AA', 5, 1, '2024-07',  5,  8, 0, 1391),
 ( 2, 2, 'N202DL', 4, 3, '2024-07',  0,  2, 0,  606),
 ( 3, 3, 'N303UA', 3, 2, '2024-07', -2,  0, 0, 1745),
 ( 4, 4, 'N404WN', 7, 6, '2024-07', 12, 15, 0,  678),
 ( 5, 1, 'N505AA', 1, 8, '2024-07',  8,  4, 0, 1093),
 ( 6, 2, 'N606DL', 4, 5, '2024-07',  0, -3, 0,  731),
 ( 7, 3, 'N303UA', 3, 7, '2024-07',  6,  9, 0, 1846),
 ( 8, 4, 'N404WN', 7, 2, '2024-07',  3,  0, 0,  337),
 ( 9, 1, 'N101AA', 5, 3, '2024-07',  0,  1, 0,  801),
 (10, 2, 'N202DL', 4, 1, '2024-07',  1,  5, 0,  760),
 -- August
 (11, 1, 'N101AA', 5, 1, '2024-08',  2,  7, 0, 1391),
 (12, 2, 'N202DL', 4, 3, '2024-08',  9, 12, 0,  606),
 (13, 3, 'N303UA', 3, 2, '2024-08',  0,  0, 0, 1745),
 (14, 4, 'N404WN', 7, 6, '2024-08', 15, 22, 0,  678),
 (15, 1, 'N505AA', 1, 8, '2024-08',  4,  6, 0, 1093),
 (16, 2, 'N606DL', 4, 5, '2024-08', -1, -2, 0,  731),
 (17, 3, 'N303UA', 3, 7, '2024-08',  8,  4, 0, 1846),
 (18, 4, 'N404WN', 7, 2, '2024-08',  5,  8, 0,  337),
 (19, 1, 'N101AA', 5, 3, '2024-08',  0,  0, 0,  801),
 (20, 2, 'N202DL', 4, 1, '2024-08',  3, 10, 0,  760),
 -- September
 (21, 1, 'N101AA', 5, 1, '2024-09',  4,  9, 0, 1391),
 (22, 2, 'N202DL', 4, 3, '2024-09',  1,  0, 0,  606),
 (23, 3, 'N303UA', 3, 2, '2024-09', -3, -5, 0, 1745),
 (24, 4, 'N404WN', 7, 6, '2024-09', 18, 25, 0,  678),
 (25, 1, 'N505AA', 1, 8, '2024-09',  6,  3, 0, 1093),
 (26, 2, 'N606DL', 4, 5, '2024-09',  2,  1, 0,  731),
 (27, 3, 'N303UA', 3, 7, '2024-09',  0,  0, 1, 1846),
 (28, 4, 'N404WN', 7, 2, '2024-09',  9, 12, 0,  337),
 (29, 1, 'N101AA', 5, 3, '2024-09',  1,  4, 0,  801),
 (30, 2, 'N202DL', 4, 1, '2024-09',  5, 11, 0,  760),
 -- October
 (31, 1, 'N101AA', 5, 1, '2024-10', 10, 14, 0, 1391),
 (32, 2, 'N202DL', 4, 3, '2024-10',  2,  6, 0,  606),
 (33, 3, 'N303UA', 3, 2, '2024-10',  0, -2, 0, 1745),
 (34, 4, 'N404WN', 7, 6, '2024-10', 21, 28, 0,  678),
 (35, 1, 'N505AA', 1, 8, '2024-10',  7,  9, 0, 1093),
 (36, 2, 'N606DL', 4, 5, '2024-10', -2,  1, 0,  731),
 (37, 3, 'N303UA', 3, 7, '2024-10', 12, 15, 0, 1846),
 (38, 4, 'N404WN', 7, 2, '2024-10',  5,  8, 0,  337),
 (39, 1, 'N101AA', 5, 3, '2024-10',  0,  0, 1,  801),
 (40, 2, 'N202DL', 4, 1, '2024-10',  6, 14, 0,  760),
 -- November — weather + winter operations start pushing delays up
 (41, 1, 'N101AA', 5, 1, '2024-11', 15, 22, 0, 1391),
 (42, 2, 'N202DL', 4, 3, '2024-11',  8, 11, 0,  606),
 (43, 3, 'N303UA', 3, 2, '2024-11',  3,  1, 0, 1745),
 (44, 4, 'N404WN', 7, 6, '2024-11', 28, 35, 0,  678),
 (45, 1, 'N505AA', 1, 8, '2024-11', 12, 18, 0, 1093),
 (46, 2, 'N606DL', 4, 5, '2024-11',  2,  6, 0,  731),
 (47, 3, 'N303UA', 3, 7, '2024-11', 18, 24, 0, 1846),
 (48, 4, 'N404WN', 7, 2, '2024-11',  9, 12, 0,  337),
 (49, 1, 'N101AA', 5, 3, '2024-11',  4, 10, 0,  801),
 (50, 2, 'N202DL', 4, 1, '2024-11', 11, 20, 0,  760),
 -- December — peak
 (51, 1, 'N101AA', 5, 1, '2024-12', 24, 32, 0, 1391),
 (52, 2, 'N202DL', 4, 3, '2024-12', 14, 18, 0,  606),
 (53, 3, 'N303UA', 3, 2, '2024-12',  5,  8, 0, 1745),
 (54, 4, 'N404WN', 7, 6, '2024-12', 45, 58, 1,  678),
 (55, 1, 'N505AA', 1, 8, '2024-12', 19, 28, 0, 1093),
 (56, 2, 'N606DL', 4, 5, '2024-12',  9, 12, 0,  731),
 (57, 3, 'N303UA', 3, 7, '2024-12', 22, 30, 0, 1846),
 (58, 4, 'N404WN', 7, 2, '2024-12', 14, 20, 0,  337),
 (59, 1, 'N101AA', 5, 3, '2024-12', 11, 16, 1,  801),
 (60, 2, 'N202DL', 4, 1, '2024-12', 17, 26, 0,  760);
