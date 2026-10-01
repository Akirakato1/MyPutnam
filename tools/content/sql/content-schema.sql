PRAGMA foreign_keys = ON;

CREATE TABLE problem (
    year INTEGER NOT NULL,
    code TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    statement_asset_path TEXT NOT NULL,
    statement_sha256 TEXT NOT NULL,
    solution_asset_path TEXT NOT NULL,
    solution_sha256 TEXT NOT NULL,
    attribution_json TEXT NOT NULL,
    PRIMARY KEY (year, code)
);

CREATE UNIQUE INDEX index_problem_year_ordinal ON problem (year, ordinal);

CREATE TABLE hint (
    year INTEGER NOT NULL,
    problem_code TEXT NOT NULL,
    hint_order INTEGER NOT NULL,
    stage TEXT NOT NULL CHECK (stage IN ('nudge', 'strategy', 'knowledge', 'outline')),
    title TEXT NOT NULL,
    asset_path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY (year, problem_code, hint_order),
    FOREIGN KEY (year, problem_code) REFERENCES problem (year, code)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX index_hint_year_problem_code ON hint (year, problem_code);

CREATE TABLE prerequisite (
    year INTEGER NOT NULL,
    problem_code TEXT NOT NULL,
    prerequisite_order INTEGER NOT NULL,
    title TEXT NOT NULL,
    asset_path TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY (year, problem_code, prerequisite_order),
    FOREIGN KEY (year, problem_code) REFERENCES problem (year, code)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX index_prerequisite_year_problem_code ON prerequisite (year, problem_code);

CREATE TABLE content_meta (
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (key)
);

PRAGMA user_version = 2;
